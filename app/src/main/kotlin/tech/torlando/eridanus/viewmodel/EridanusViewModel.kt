// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.UnicodeString
import co.nstant.`in`.cbor.model.UnsignedInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.torlando.eridanus.EridanusApp
import tech.torlando.eridanus.rns.RnsAnnounceHandler
import tech.torlando.eridanus.rns.RnsAnnounceHandlerRegistration
import tech.torlando.eridanus.rns.RnsBackend
import tech.torlando.eridanus.rns.RnsBackendConfig
import tech.torlando.eridanus.rns.RnsIdentity
import tech.torlando.eridanus.data.DarkModeOption
import tech.torlando.eridanus.data.DefaultRoomConfig
import tech.torlando.eridanus.data.IdentityStore
import tech.torlando.eridanus.data.PreferencesManager
import tech.torlando.eridanus.data.db.EridanusDatabase
import tech.torlando.eridanus.data.db.HubEntity
import tech.torlando.eridanus.rrc.RrcClient
import tech.torlando.eridanus.rrc.RrcConstants
import tech.torlando.eridanus.rrc.RrcEvent
import tech.torlando.eridanus.rrc.RrcHub
import tech.torlando.eridanus.service.EridanusConnectionService
import tech.torlando.eridanus.ui.theme.PresetTheme
import java.io.ByteArrayInputStream

data class DiscoveredHub(
    val hash: ByteArray,
    val name: String,
    val lastSeen: Long = System.currentTimeMillis(),
    val starred: Boolean = false,
) {
    val hexHash: String get() = hash.joinToString("") { "%02x".format(it) }
}

data class AvailableRoom(val name: String, val topic: String?)

data class RoomMember(val nick: String?, val hashPrefix: String)

data class ChatMessage(
    val nick: String?,
    val body: String,
    val src: ByteArray?,
    val timestamp: Long = System.currentTimeMillis(),
    val isNotice: Boolean = false,
    val isError: Boolean = false,
)

class EridanusViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "EridanusViewModel"

        // Cadence for the shared-instance watchdog (see
        // [startSharedInstanceWatchdog]). 10s mirrors the value the original
        // auto-recover commit (fix/auto-recover-shared-instance-attach,
        // 44e6c61) settled on after manual testing on Samsung S21 — tight
        // enough that swapping hosts feels responsive, loose enough that one
        // loopback TCP connect every 10s costs nothing meaningful for
        // battery.
        private const val WATCHDOG_INTERVAL_MS = 10_000L

        // Brief grace period after restart() returns before re-reading
        // backend flags. The backend impl already waits internally for its
        // service to come up, but the ViewModel-side re-read still gets
        // racy intermediate state if it happens the same instant the
        // service flips its flag.
        private const val POST_RESTART_SETTLE_MS = 300L
    }

    private val backend: RnsBackend = (application as EridanusApp).rnsBackend
    /** "kotlin" or "python" — surfaces in the SharedInstanceBannerCard so
     * trust-skeptical users on the python flavor can confirm the reference
     * stack is what's running. */
    val backendIdentifier: String = backend.identifier
    private val prefs = PreferencesManager(application)
    private val identityStore = IdentityStore(application, backend.identities)
    private val hubDao = EridanusDatabase.getInstance(application).hubDao()

    val theme: StateFlow<PresetTheme> = prefs.theme.stateIn(
        viewModelScope, SharingStarted.Eagerly, PresetTheme.ERIDANUS
    )

    val darkMode: StateFlow<DarkModeOption> = prefs.darkMode.stateIn(
        viewModelScope, SharingStarted.Eagerly, DarkModeOption.SYSTEM
    )

    val nickname: StateFlow<String> = prefs.nickname.stateIn(
        viewModelScope, SharingStarted.Eagerly, "Anonymous Peer"
    )

    val hubName: StateFlow<String> = prefs.hubName.stateIn(
        viewModelScope, SharingStarted.Eagerly, "Eridanus Hub"
    )

    // Reticulum state
    private val _reticulumStarted = MutableStateFlow(false)
    val reticulumStarted: StateFlow<Boolean> = _reticulumStarted

    private val _connectedToSharedInstance = MutableStateFlow(false)
    val connectedToSharedInstance: StateFlow<Boolean> = _connectedToSharedInstance

    /** Sticky: was true, lost the host, hasn't reconnected yet. Drives the
     * "lost host — reconnecting" banner state borrowed from v0.10.x columba.
     * Cleared on successful re-attach. */
    private val _wasConnectedToSharedInstance = MutableStateFlow(false)
    val wasConnectedToSharedInstance: StateFlow<Boolean> = _wasConnectedToSharedInstance

    /** True while [restartBackend] is mid-cycle. UI uses this to show a
     * progress affordance instead of the retry button so the user doesn't
     * hit retry repeatedly during an in-flight restart. */
    private val _isRestarting = MutableStateFlow(false)
    val isRestarting: StateFlow<Boolean> = _isRestarting

    // Client state
    private var clientEventJob: kotlinx.coroutines.Job? = null
    private var rrcClient: RrcClient? = null
    private val _clientState = MutableStateFlow(tech.torlando.eridanus.rrc.ClientState.DISCONNECTED)
    val clientState: StateFlow<tech.torlando.eridanus.rrc.ClientState> = _clientState

    private val _connectedHubName = MutableStateFlow<String?>(null)
    val connectedHubName: StateFlow<String?> = _connectedHubName

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError

    private val _joinedRooms = MutableStateFlow<Set<String>>(emptySet())
    val joinedRooms: StateFlow<Set<String>> = _joinedRooms

    private val _currentRoom = MutableStateFlow<String?>(null)
    val currentRoom: StateFlow<String?> = _currentRoom

    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts

    private val _availableRooms = MutableStateFlow<List<AvailableRoom>>(emptyList())
    val availableRooms: StateFlow<List<AvailableRoom>> = _availableRooms

    private val _hubGreetingMessage = MutableStateFlow<String?>(null)
    val hubGreetingMessage: StateFlow<String?> = _hubGreetingMessage

    private val _roomTopics = MutableStateFlow<Map<String, String?>>(emptyMap())
    val roomTopics: StateFlow<Map<String, String?>> = _roomTopics

    private val _roomMemberCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val roomMemberCounts: StateFlow<Map<String, Int>> = _roomMemberCounts

    private val _roomMemberList = MutableStateFlow<Map<String, List<RoomMember>>>(emptyMap())
    val roomMemberList: StateFlow<Map<String, List<RoomMember>>> = _roomMemberList

    // Hub browser (persisted via Room)
    val discoveredHubs: StateFlow<List<DiscoveredHub>> = hubDao.observeAll()
        .map { entities ->
            Log.d(TAG, "Hub flow emitted: ${entities.map { "${it.name}:starred=${it.starred}" }}")
            entities.map { DiscoveredHub(hash = it.hash, name = it.name, lastSeen = it.lastSeen, starred = it.starred) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Hub hosting
    private var rrcHub: RrcHub? = null
    private val _hubRunning = MutableStateFlow(false)
    val hubRunning: StateFlow<Boolean> = _hubRunning

    private val _hubClients = MutableStateFlow(0)
    val hubClients: StateFlow<Int> = _hubClients

    private val _hubDestHash = MutableStateFlow<ByteArray?>(null)
    val hubDestHash: StateFlow<ByteArray?> = _hubDestHash

    val announceInterval: StateFlow<Int> = prefs.announceInterval.stateIn(
        viewModelScope, SharingStarted.Eagerly, 0
    )

    val hubGreeting: StateFlow<String> = prefs.hubGreeting.stateIn(
        viewModelScope, SharingStarted.Eagerly, ""
    )

    val hubDefaultRooms: StateFlow<List<DefaultRoomConfig>> = prefs.hubDefaultRooms.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList()
    )

    // Default true to avoid flash while DataStore loads
    val hasCompletedOnboarding: StateFlow<Boolean> = prefs.hasCompletedOnboarding.stateIn(
        viewModelScope, SharingStarted.Eagerly, true
    )

    // Identities
    private var hubIdentity: RnsIdentity? = null
    private var clientIdentity: RnsIdentity? = null

    // Reticulum lifecycle coordination.
    // - restartMutex serializes teardown+bring-up so the auto-recovery
    //   watchdog and the user-driven retry button never race each other
    //   to recreate the backend service.
    // - watchdogJob is the periodic probe that detects when the
    //   shared-instance host on 127.0.0.1:37428 appears (we started
    //   without one) or disappears (host died after we attached), and
    //   re-runs the attach probe so the user never has to think about
    //   launch order.
    private val restartMutex = Mutex()
    private var watchdogJob: Job? = null

    // This ViewModel's announce-handler registration with the backend.
    // [registerAnnounceHandler] deregisters the previous one before
    // registering a new one, and [onCleared] deregisters on teardown —
    // without that, every restartBackend() cycle and every ViewModel
    // recreation would leave a stale handler in RNS's handler list,
    // pinning a leaked ViewModel.
    private var announceHandlerRegistration: RnsAnnounceHandlerRegistration? = null

    init {
        initReticulum()
        observeNotificationStatus()
    }

    private fun initReticulum() {
        viewModelScope.launch(Dispatchers.IO) {
            bringUpReticulum()
            EridanusConnectionService.start(getApplication())
            startSharedInstanceWatchdog()
        }
    }

    /**
     * Drive one backend bring-up cycle and reflect the result into
     * [_reticulumStarted] / [_connectedToSharedInstance].
     *
     * Idempotent w.r.t. backend.start: if a service is already running
     * with the same config the start call is effectively a no-op (foreground
     * service intent re-delivers but the backend short-circuits via its
     * own CAS guard). Use [restartBackend] when you need a fresh
     * shared-instance probe — that's the path that tears down the service
     * first so the probe actually re-runs.
     */
    private suspend fun bringUpReticulum() {
        try {
            if (backend.isRunning) {
                // The backend's foreground service + RNS instance are
                // already up — this is a ViewModel recreation (Android
                // destroyed and recreated the Activity while the app was
                // backgrounded), NOT a cold start. Calling backend.start()
                // again would re-fire the foreground service's
                // onStartCommand and, on the python flavor, re-initialize
                // the live RNS instance — dropping the shared-instance
                // attachment and churning announce handlers on every
                // foreground. Instead just resync UI state from the
                // still-running backend and re-register this ViewModel's
                // announce handler. No backend.start(), no 2s cold-start
                // delay (so the "Starting Reticulum" flash goes away).
                loadIdentitiesIfNeeded()
                _reticulumStarted.value = true
                val connected = backend.connectedToSharedInstance
                _connectedToSharedInstance.value = connected
                if (connected) {
                    _wasConnectedToSharedInstance.value = false
                    registerAnnounceHandler()
                }
                Log.i(
                    TAG,
                    "Reticulum already running (backend=${backend.identifier}, " +
                        "shared instance: $connected) — resynced without restart"
                )
                return
            }

            // Cold start. Bring up Reticulum via the chosen backend (see
            // :eridanus-rns-api and the per-flavor source-set wiring in
            // EridanusApp). The kotlin flavor delegates to rns-android's
            // foreground service, which wires Room-backed IdentityStore /
            // PathStore / AnnounceStore so known destinations / ratchets /
            // announce-cache stay in SQLite instead of /.reticulum/* on root
            // fs (which the old direct Reticulum.start() path silently fell
            // back to on Android because `user.home` is empty). The python
            // flavor does the equivalent via upstream python Reticulum
            // running as a shared-instance client through chaquopy.
            //
            // shareInstance = false: Eridanus is a chat client with no UI for
            // configuring Reticulum interfaces. If we became the
            // shared-instance server, our Reticulum would have no path to the
            // outside world, and any sibling process that attached to us
            // (Sideband, Carina, the rns CLI) would also be cut off — they'd
            // see their own outbound interfaces disabled in client mode and
            // rely on ours, which is empty. So we explicitly opt out of ever
            // hosting; [startSharedInstanceWatchdog] re-drives the probe if a
            // host comes up after Eridanus does.
            backend.start(
                getApplication(),
                RnsBackendConfig(shareInstance = false),
            )

            // Service init kicks off Reticulum.start(...) + interface
            // bring-up on a background thread; give it a beat before we read
            // identityStore. 2s mirrors the kotlin backend's initial bring-up
            // window.
            delay(2000)

            loadIdentitiesIfNeeded()

            _reticulumStarted.value = backend.isRunning
            val connected = backend.connectedToSharedInstance
            _connectedToSharedInstance.value = connected
            if (connected) {
                // Successful (re)attach clears the lost-host banner.
                _wasConnectedToSharedInstance.value = false
                registerAnnounceHandler()
            }
            Log.i(
                TAG,
                "Reticulum started (backend=${backend.identifier}, shared instance: $connected)"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Reticulum", e)
        }
    }

    /**
     * Identities are persistent across restarts; only generate on the
     * first ever start. Subsequent restartBackend() cycles and ViewModel
     * recreations reuse the same identities so the peer-identity surface a
     * hub sees doesn't churn whenever the user backgrounds the app or
     * toggles a shared-instance host.
     */
    private fun loadIdentitiesIfNeeded() {
        if (hubIdentity == null) {
            hubIdentity = identityStore.loadHubIdentity()
                ?: backend.identities.create().also { identityStore.saveHubIdentity(it) }
        }
        if (clientIdentity == null) {
            clientIdentity = identityStore.loadClientIdentity()
                ?: backend.identities.create().also { identityStore.saveClientIdentity(it) }
        }
    }

    /**
     * Tear the backend's host service down and bring it back up.
     * Serialized through [restartMutex] so concurrent watchdog ticks +
     * user-tap retry never collide.
     *
     * The whole cycle (stop → wait for teardown → restart probe → start →
     * wait for service to be ready) is handled inside [backend.restart].
     * Here we just bracket it with the UI flag flips and re-read the
     * resulting state.
     */
    private suspend fun restartBackend() {
        if (!restartMutex.tryLock()) {
            // Another restart cycle is already in flight (watchdog and a
            // user tap landed within the same second). Drop this one —
            // the in-flight cycle is going to refresh the state we care
            // about anyway.
            Log.d(TAG, "restartBackend: already in flight, skipping")
            return
        }
        try {
            _isRestarting.value = true
            _reticulumStarted.value = false
            _connectedToSharedInstance.value = false

            backend.restart(getApplication(), RnsBackendConfig(shareInstance = false))
            delay(POST_RESTART_SETTLE_MS)

            _reticulumStarted.value = backend.isRunning
            val connected = backend.connectedToSharedInstance
            _connectedToSharedInstance.value = connected
            if (connected) {
                _wasConnectedToSharedInstance.value = false
                registerAnnounceHandler()
            }
            Log.i(TAG, "Reticulum restart complete (shared instance: $connected)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart Reticulum", e)
        } finally {
            _isRestarting.value = false
            restartMutex.unlock()
        }
    }

    /**
     * Periodic watchdog that compares actual shared-instance topology
     * (is something bound to 127.0.0.1:37428 right now?) to our current
     * attached state, and triggers [restartBackend] whenever they
     * disagree. Lets the user start, stop, or swap shared-instance host
     * apps after Eridanus is already running without relaunching.
     *
     * Cases this handles:
     *
     * - Eridanus started before the host: probe goes false→true as soon
     *   as the user launches Sideband / Carina / rnsd, watchdog re-runs
     *   the attach probe. ~10s recovery in practice.
     * - Host killed while Eridanus is attached: probe goes true→false,
     *   watchdog flips us back to standalone and sets
     *   [_wasConnectedToSharedInstance] so the lost-host banner shows.
     * - Host swapped (Sideband off, different app takes the port):
     *   probe oscillates false→true, watchdog re-attaches to whoever is
     *   bound now. Same stored identity, so peers keep recognising us.
     */
    private fun startSharedInstanceWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                try {
                    val probe = backend.isSharedInstanceRunning()
                    val attached = _connectedToSharedInstance.value
                    if (probe != attached) {
                        if (attached && !probe) {
                            // We were attached; host went away. Set the
                            // sticky flag before the restart wipes our
                            // current attach state so the banner can show
                            // the lost-host card during the recovery
                            // attempt.
                            _wasConnectedToSharedInstance.value = true
                        }
                        Log.i(
                            TAG,
                            "Watchdog: shared-instance topology changed " +
                                "(probe=$probe, attached=$attached) — restarting backend",
                        )
                        restartBackend()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Watchdog probe failed: ${e.message}")
                }
            }
        }
    }

    private fun observeNotificationStatus() {
        viewModelScope.launch {
            combine(
                _reticulumStarted,
                _connectedToSharedInstance,
                _hubRunning,
                _hubClients,
                _clientState,
                discoveredHubs,
            ) { args ->
                @Suppress("UNCHECKED_CAST")
                val started = args[0] as Boolean
                val sharedInstance = args[1] as Boolean
                val hosting = args[2] as Boolean
                val clients = args[3] as Int
                val client = args[4] as tech.torlando.eridanus.rrc.ClientState
                val hubs = args[5] as List<DiscoveredHub>

                when {
                    !started -> "Starting\u2026"
                    hosting && client == tech.torlando.eridanus.rrc.ClientState.ACTIVE ->
                        "Hosting hub \u00b7 $clients connected \u00b7 joined a hub"
                    hosting ->
                        "Hosting hub \u00b7 $clients connected"
                    client == tech.torlando.eridanus.rrc.ClientState.ACTIVE ->
                        "Connected to hub"
                    client == tech.torlando.eridanus.rrc.ClientState.CONNECTING ||
                    client == tech.torlando.eridanus.rrc.ClientState.AWAITING_WELCOME ->
                        "Connecting to hub\u2026"
                    hubs.isNotEmpty() ->
                        "${hubs.size} hub${if (hubs.size == 1) "" else "s"} discovered"
                    sharedInstance -> "Listening via shared instance"
                    else -> "Listening (standalone)"
                }
            }.collect { text ->
                EridanusConnectionService.updateStatus(text)
            }
        }
    }

    /**
     * User-driven retry from the "No shared instance — tap to retry"
     * banner. Delegates to [restartBackend], which is what the background
     * watchdog also uses, so the two paths share a single serialized
     * teardown+bring-up cycle.
     */
    fun retrySharedInstance() {
        viewModelScope.launch(Dispatchers.IO) {
            restartBackend()
        }
    }


    private fun registerAnnounceHandler() {
        // Drop the previous registration first. This is called on every
        // bringUpReticulum() (cold start + ViewModel-recreation resync) and
        // every restartBackend() cycle — without deregistering, each call
        // appends another handler to RNS's announce_handlers list, and each
        // stale handler captures `this` and keeps firing redundant
        // hubDao upserts.
        announceHandlerRegistration?.deregister()
        announceHandlerRegistration = null
        try {
            val handler = RnsAnnounceHandler { destHash, _, appData ->
                handleHubAnnounce(destHash, appData)
                true
            }
            announceHandlerRegistration = backend.transport.registerAnnounceHandler(handler)
            Log.d(TAG, "Announce handler registered for ${RrcConstants.DEST_NAME}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register announce handler", e)
        }
    }

    private fun handleHubAnnounce(destHash: ByteArray, appData: ByteArray?) {
        if (appData == null || appData.isEmpty()) return

        var proto: String? = null
        var hubName: String? = null
        // Guard against non-CBOR appData that can cause the decoder to allocate huge arrays
        if (appData.size > 1024) return

        try {
            val items = CborDecoder(ByteArrayInputStream(appData)).decode()
            if (items.isNotEmpty()) {
                val map = items[0] as? CborMap ?: return
                for (key in map.keys) {
                    when (key) {
                        // String-keyed format (rrcd canonical: {"proto":"rrc","v":1,"hub":"..."})
                        is UnicodeString -> when (key.string) {
                            "proto" -> proto = (map.get(key) as? UnicodeString)?.string
                            "hub" -> hubName = (map.get(key) as? UnicodeString)?.string
                        }
                        // Integer-keyed fallback
                        is UnsignedInteger -> when (key.value.toInt()) {
                            0 -> proto = (map.get(key) as? UnicodeString)?.string
                            2 -> hubName = (map.get(key) as? UnicodeString)?.string
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            return // Not valid CBOR — not an RRC announce
        }

        // Only accept announces with proto == "rrc"
        if (proto != "rrc") return
        if (hubName == null) hubName = "Unknown Hub"

        val hashCopy = destHash.copyOf()
        val hexHash = hashCopy.joinToString("") { "%02x".format(it) }

        viewModelScope.launch(Dispatchers.IO) {
            hubDao.upsertPreserveStarred(hexHash, hashCopy, hubName, System.currentTimeMillis())
        }
    }

    fun connectToHub(hubHash: ByteArray) {
        val id = clientIdentity ?: return
        _connectionError.value = null
        clientEventJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                EridanusConnectionService.start(getApplication())
                val client = RrcClient(id, backend, nickname = nickname.value.ifEmpty { null })
                rrcClient = client
                _clientState.value = tech.torlando.eridanus.rrc.ClientState.CONNECTING

                // Collect events
                clientEventJob = launch {
                    client.events.collect { event ->
                        handleRrcEvent(event)
                    }
                }

                client.connect(hubHash)

                // Connection timeout: if still not ACTIVE after 30s, give up
                launch {
                    delay(30_000)
                    if (_clientState.value == tech.torlando.eridanus.rrc.ClientState.CONNECTING ||
                        _clientState.value == tech.torlando.eridanus.rrc.ClientState.AWAITING_WELCOME
                    ) {
                        Log.w(TAG, "Connection timed out after 30s")
                        client.disconnect()
                        rrcClient = null
                        _clientState.value = tech.torlando.eridanus.rrc.ClientState.DISCONNECTED
                        _connectionError.value = "Connection timed out"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to hub", e)
                _clientState.value = tech.torlando.eridanus.rrc.ClientState.DISCONNECTED
            }
        }
    }

    fun clearConnectionError() {
        _connectionError.value = null
    }

    fun disconnectFromHub() {
        clientEventJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            rrcClient?.disconnect()
            rrcClient = null
            _clientState.value = tech.torlando.eridanus.rrc.ClientState.DISCONNECTED
            _connectedHubName.value = null
            _joinedRooms.value = emptySet()
            _currentRoom.value = null
            _messages.value = emptyMap()
            _unreadCounts.value = emptyMap()
            _availableRooms.value = emptyList()
            _roomTopics.value = emptyMap()
            _roomMemberCounts.value = emptyMap()
            _roomMemberList.value = emptyMap()
            _hubGreetingMessage.value = null
            if (!_hubRunning.value) {
                EridanusConnectionService.stop(getApplication())
            }
        }
    }

    fun joinRoom(room: String, key: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rrcClient?.join(room, key = key?.ifEmpty { null })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to join room", e)
            }
        }
    }

    fun partRoom(room: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rrcClient?.part(room)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to part room", e)
            }
        }
    }

    fun sendInput(text: String) {
        val room = _currentRoom.value ?: return
        val trimmed = text.trim()
        if (trimmed.startsWith("/")) {
            val parts = trimmed.split(" ", limit = 2)
            val cmd = parts[0].lowercase()
            val rest = parts.getOrNull(1) ?: ""
            val roomScoped = setOf(
                "/topic", "/who", "/names", "/kick", "/ban",
                "/op", "/deop", "/voice", "/devoice",
                "/mode", "/register", "/unregister", "/invite"
            )
            if (cmd == "/help") {
                showHelpMessage(room)
                return
            }
            val commandRoom = if (cmd in roomScoped) room else null
            // Hub expects room name as first arg for room-scoped commands;
            // inject it so users don't have to type it manually.
            val commandText = if (cmd in roomScoped) {
                if (rest.isEmpty()) "$cmd $room" else "$cmd $room $rest"
            } else {
                trimmed
            }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    rrcClient?.sendCommand(commandText, room = commandRoom)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send command", e)
                }
            }
        } else {
            sendMessage(room, trimmed)
        }
    }

    private fun sendMessage(room: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rrcClient?.sendMessage(room, text)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
            }
        }
    }

    private fun showHelpMessage(room: String) {
        val helpText = """Available commands:
/topic [text] — view or set room topic
/who — list room members
/kick <user> — kick a user
/ban add|del|list [user] — manage bans
/op <user> — grant operator status
/deop <user> — remove operator status
/voice <user> — grant voice
/devoice <user> — remove voice
/mode [+/-flags] — view or set room modes
/register — register the room
/unregister — unregister the room
/invite add|del|list [user] — manage invites
/nick <name> — change your nickname
/list — list public rooms"""
        addMessage(room, ChatMessage(
            nick = null,
            body = helpText,
            src = null,
            isNotice = true,
        ))
    }

    fun setCurrentRoom(room: String?) {
        _currentRoom.value = room
        if (room != null) {
            val counts = _unreadCounts.value.toMutableMap()
            counts.remove(room)
            _unreadCounts.value = counts
        }
    }

    fun refreshMemberList(room: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rrcClient?.sendCommand("/who $room", room = room)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request member list", e)
            }
        }
    }

    fun requestRoomList() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rrcClient?.sendCommand("/list")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request room list", e)
            }
        }
    }

    fun setTheme(theme: PresetTheme) {
        viewModelScope.launch { prefs.setTheme(theme) }
    }

    fun setDarkMode(option: DarkModeOption) {
        viewModelScope.launch { prefs.setDarkMode(option) }
    }

    fun setNickname(nick: String) {
        viewModelScope.launch {
            prefs.setNickname(nick)
            rrcClient?.nickname = nick.ifEmpty { null }
        }
    }

    fun setHubName(name: String) {
        viewModelScope.launch {
            prefs.setHubName(name)
            rrcHub?.hubName = name
        }
    }

    fun setHubGreeting(greeting: String) {
        viewModelScope.launch {
            prefs.setHubGreeting(greeting)
            rrcHub?.greeting = greeting.ifEmpty { null }
        }
    }

    fun setHubDefaultRooms(rooms: List<DefaultRoomConfig>) {
        viewModelScope.launch { prefs.setHubDefaultRooms(rooms) }
    }

    fun completeOnboarding() {
        viewModelScope.launch { prefs.setHasCompletedOnboarding(true) }
    }

    fun connectToOwnHub() {
        val hash = _hubDestHash.value ?: run {
            Log.w(TAG, "connectToOwnHub: no hub dest hash")
            return
        }
        val hubId = hubIdentity ?: run {
            Log.w(TAG, "connectToOwnHub: no hub identity")
            return
        }
        val clientId = clientIdentity ?: run {
            Log.w(TAG, "connectToOwnHub: no client identity")
            return
        }
        Log.i(TAG, "connectToOwnHub: hash=${hash.joinToString("") { "%02x".format(it) }}")
        _connectionError.value = null
        clientEventJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                EridanusConnectionService.start(getApplication())
                val client = RrcClient(clientId, backend, nickname = nickname.value.ifEmpty { null })
                rrcClient = client
                _clientState.value = tech.torlando.eridanus.rrc.ClientState.CONNECTING

                clientEventJob = launch {
                    client.events.collect { event ->
                        handleRrcEvent(event)
                    }
                }

                client.connect(hash, knownIdentity = hubId)

                // Connection timeout
                launch {
                    delay(30_000)
                    if (_clientState.value == tech.torlando.eridanus.rrc.ClientState.CONNECTING ||
                        _clientState.value == tech.torlando.eridanus.rrc.ClientState.AWAITING_WELCOME
                    ) {
                        Log.w(TAG, "Own hub connection timed out after 30s")
                        client.disconnect()
                        rrcClient = null
                        _clientState.value = tech.torlando.eridanus.rrc.ClientState.DISCONNECTED
                        _connectionError.value = "Connection timed out"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to own hub", e)
                _clientState.value = tech.torlando.eridanus.rrc.ClientState.DISCONNECTED
            }
        }
    }

    fun announceHub() {
        rrcHub?.announce()
    }

    fun toggleHubStar(hexHash: String) {
        Log.i(TAG, "toggleHubStar: $hexHash")
        viewModelScope.launch(Dispatchers.IO) {
            hubDao.toggleStarred(hexHash)
            Log.i(TAG, "toggleStarred done for $hexHash")
        }
    }

    fun setAnnounceInterval(seconds: Int) {
        viewModelScope.launch {
            prefs.setAnnounceInterval(seconds)
            val hub = rrcHub
            if (hub != null && _hubRunning.value) {
                hub.stopAnnounceLoop()
                if (seconds > 0) {
                    hub.startAnnounceLoop(seconds, viewModelScope)
                }
            }
        }
    }

    fun startHub() {
        val id = hubIdentity ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hub = RrcHub(id, backend, prefs.getHubName())
                hub.greeting = prefs.getHubGreeting().ifEmpty { null }
                hub.defaultRooms = prefs.getHubDefaultRooms()
                    .filter { it.name.isNotBlank() }
                // Hub owner is always a server operator
                clientIdentity?.let { hub.roomManager.addServerOp(it.hash) }
                rrcHub = hub

                launch {
                    hub.connectedClients.collect { count ->
                        _hubClients.value = count
                    }
                }

                hub.start()
                _hubRunning.value = true
                _hubDestHash.value = hub.destHash
                EridanusConnectionService.start(getApplication())

                val interval = announceInterval.value
                if (interval > 0) {
                    hub.startAnnounceLoop(interval, viewModelScope)
                }
                hub.startPingLoop(viewModelScope)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start hub", e)
            }
        }
    }

    fun stopHub() {
        viewModelScope.launch(Dispatchers.IO) {
            rrcHub?.stop()
            rrcHub = null
            _hubRunning.value = false
            _hubClients.value = 0
            _hubDestHash.value = null
            if (_clientState.value == tech.torlando.eridanus.rrc.ClientState.DISCONNECTED) {
                EridanusConnectionService.stop(getApplication())
            }
        }
    }

    private fun handleRrcEvent(event: RrcEvent) {
        when (event) {
            is RrcEvent.Welcome -> {
                Log.i(TAG, "Welcome: hubName=${event.hubName}")
                _clientState.value = tech.torlando.eridanus.rrc.ClientState.ACTIVE
                _connectedHubName.value = event.hubName
                Log.i(TAG, "Requesting room list after welcome")
                requestRoomList()
            }

            is RrcEvent.Joined -> {
                Log.d(TAG, "Joined: room=${event.room} members=${event.members?.size}")
                val rooms = _joinedRooms.value.toMutableSet()
                rooms.add(event.room)
                _joinedRooms.value = rooms
                if (_currentRoom.value == null) {
                    _currentRoom.value = event.room
                }
                if (event.members != null) {
                    val counts = _roomMemberCounts.value.toMutableMap()
                    counts[event.room] = event.members.size
                    _roomMemberCounts.value = counts
                }
                addMessage(event.room, ChatMessage(
                    nick = null,
                    body = "Joined #${event.room}",
                    src = null,
                    isNotice = true,
                ))
                // Auto-request /who after joining
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        rrcClient?.sendCommand("/who ${event.room}", room = event.room)
                    } catch (_: Exception) {}
                }
            }

            is RrcEvent.Parted -> {
                val rooms = _joinedRooms.value.toMutableSet()
                rooms.remove(event.room)
                _joinedRooms.value = rooms
                if (_currentRoom.value == event.room) {
                    _currentRoom.value = rooms.firstOrNull()
                }
                addMessage(event.room, ChatMessage(
                    nick = null,
                    body = "Left #${event.room}",
                    src = null,
                    isNotice = true,
                ))
            }

            is RrcEvent.MessageReceived -> {
                addMessage(event.room, ChatMessage(
                    nick = event.nick,
                    body = event.body,
                    src = event.src,
                ))
                if (_currentRoom.value != event.room) {
                    val counts = _unreadCounts.value.toMutableMap()
                    counts[event.room] = (counts[event.room] ?: 0) + 1
                    _unreadCounts.value = counts
                }
            }

            is RrcEvent.NoticeReceived -> {
                Log.i(TAG, "NoticeReceived: room=${event.room} body='${event.body}'")
                if (event.room == null) {
                    // Parse room list from either Ara hub ("Registered public rooms:")
                    // or Python rrc-nomadnet hub ("Rooms:")
                    if (event.body.startsWith("Registered public rooms:") ||
                        event.body.startsWith("Rooms:")
                    ) {
                        val rooms = event.body.lines().drop(1).mapNotNull { line ->
                            val trimmed = line.trim()
                            if (trimmed.isEmpty()) return@mapNotNull null
                            // Python hub format: "lobby (2 members) - General chat"
                            // Ara hub format:    "lobby - General chat"
                            // Strip "(N members)" if present
                            val stripped = trimmed.replace(Regex("""\s*\(\d+ members?\)"""), "")
                            val parts = stripped.split(" - ", limit = 2)
                            AvailableRoom(
                                name = parts[0].trim(),
                                topic = parts.getOrNull(1)?.trim(),
                            )
                        }
                        Log.i(TAG, "Parsed ${rooms.size} available rooms: ${rooms.map { it.name }}")
                        _availableRooms.value = rooms
                        _currentRoom.value?.let { displayRoom ->
                            addMessage(displayRoom, ChatMessage(nick = null, body = event.body, src = null, isNotice = true))
                        }
                        return
                    } else if (event.body == "No public rooms registered" ||
                               event.body == "no rooms"
                    ) {
                        Log.i(TAG, "No rooms registered on hub")
                        _availableRooms.value = emptyList()
                        _currentRoom.value?.let { displayRoom ->
                            addMessage(displayRoom, ChatMessage(nick = null, body = event.body, src = null, isNotice = true))
                        }
                        return
                    } else {
                        // Roomless notice that isn't /list — treat as hub greeting
                        _hubGreetingMessage.value = event.body
                        return
                    }
                }
                // Parse topic notices
                parseTopicNotice(event.body)
                // Parse /who response for member count
                parseMembersNotice(event.body)

                val room = event.room ?: _currentRoom.value ?: return
                addMessage(room, ChatMessage(
                    nick = null,
                    body = event.body,
                    src = null,
                    isNotice = true,
                ))
            }

            is RrcEvent.ErrorReceived -> {
                val room = event.room ?: _currentRoom.value ?: return
                addMessage(room, ChatMessage(
                    nick = null,
                    body = "Error: ${event.text}",
                    src = null,
                    isError = true,
                ))
            }

            is RrcEvent.MemberJoined -> {
                val shortHash = event.memberHash.take(6).joinToString("") { "%02x".format(it) }
                addMessage(event.room, ChatMessage(
                    nick = null,
                    body = "$shortHash joined",
                    src = null,
                    isNotice = true,
                ))
                val counts = _roomMemberCounts.value.toMutableMap()
                counts[event.room] = (counts[event.room] ?: 0) + 1
                _roomMemberCounts.value = counts
            }

            is RrcEvent.MemberParted -> {
                val shortHash = event.memberHash.take(6).joinToString("") { "%02x".format(it) }
                addMessage(event.room, ChatMessage(
                    nick = null,
                    body = "$shortHash left",
                    src = null,
                    isNotice = true,
                ))
                val counts = _roomMemberCounts.value.toMutableMap()
                val current = counts[event.room] ?: 0
                if (current > 0) counts[event.room] = current - 1
                _roomMemberCounts.value = counts
            }

            is RrcEvent.Disconnected -> {
                Log.i(TAG, "Disconnected event received, clearing rooms")
                _clientState.value = tech.torlando.eridanus.rrc.ClientState.DISCONNECTED
                _connectedHubName.value = null
                _joinedRooms.value = emptySet()
                _availableRooms.value = emptyList()
                _roomTopics.value = emptyMap()
                _roomMemberCounts.value = emptyMap()
                _roomMemberList.value = emptyMap()
                _hubGreetingMessage.value = null
                if (!_hubRunning.value) {
                    EridanusConnectionService.stop(getApplication())
                }
            }

            is RrcEvent.ConnectionFailed -> {
                _clientState.value = tech.torlando.eridanus.rrc.ClientState.DISCONNECTED
                _connectionError.value = event.reason
            }
        }
    }

    private fun parseTopicNotice(body: String) {
        // "topic for {room}: {text}" or "topic for {room}: (none)"
        val topicFor = Regex("""^topic for (\S+): (.+)$""")
        topicFor.find(body)?.let { match ->
            val room = match.groupValues[1]
            val text = match.groupValues[2]
            val topics = _roomTopics.value.toMutableMap()
            topics[room] = if (text == "(none)") null else text
            _roomTopics.value = topics
            return
        }
        // "topic for {room} is now: {text}" or "...is now: (cleared)"
        val topicNow = Regex("""^topic for (\S+) is now: (.+)$""")
        topicNow.find(body)?.let { match ->
            val room = match.groupValues[1]
            val text = match.groupValues[2]
            val topics = _roomTopics.value.toMutableMap()
            topics[room] = if (text == "(cleared)") null else text
            _roomTopics.value = topics
            return
        }
        // "room {room}: registered; mode=...; topic=..."
        val roomInfo = Regex("""^room (\S+):.*topic=(.+)$""")
        roomInfo.find(body)?.let { match ->
            val room = match.groupValues[1]
            val text = match.groupValues[2].trim()
            val topics = _roomTopics.value.toMutableMap()
            topics[room] = text.ifEmpty { null }
            _roomTopics.value = topics
        }
    }

    private fun parseMembersNotice(body: String) {
        // "members in {room}: nick1 (hash1), nick2 (hash2)" or "(none)"
        val membersIn = Regex("""^members in (\S+): (.+)$""")
        membersIn.find(body)?.let { match ->
            val room = match.groupValues[1]
            val memberList = match.groupValues[2]
            if (memberList == "(none)") {
                val counts = _roomMemberCounts.value.toMutableMap()
                counts[room] = 0
                _roomMemberCounts.value = counts
                val members = _roomMemberList.value.toMutableMap()
                members[room] = emptyList()
                _roomMemberList.value = members
                return
            }
            // Parse "nick (hash12hex), nick2 (hash12hex)" or bare "fullhex"
            val entryPattern = Regex("""(.+?)\s+\(([0-9a-f]+)\)""")
            val parsed = memberList.split(",").map { entry ->
                val trimmed = entry.trim()
                val entryMatch = entryPattern.find(trimmed)
                if (entryMatch != null) {
                    RoomMember(nick = entryMatch.groupValues[1], hashPrefix = entryMatch.groupValues[2])
                } else {
                    RoomMember(nick = null, hashPrefix = trimmed)
                }
            }
            val counts = _roomMemberCounts.value.toMutableMap()
            counts[room] = parsed.size
            _roomMemberCounts.value = counts
            val members = _roomMemberList.value.toMutableMap()
            members[room] = parsed
            _roomMemberList.value = members
        }
    }

    private fun addMessage(room: String, message: ChatMessage) {
        val current = _messages.value.toMutableMap()
        val roomMessages = (current[room] ?: emptyList()).toMutableList()
        roomMessages.add(message)
        // Keep max 500 messages per room
        if (roomMessages.size > 500) {
            roomMessages.removeAt(0)
        }
        current[room] = roomMessages
        _messages.value = current
    }

    override fun onCleared() {
        super.onCleared()
        // Deregister our announce handler — it captures `this`, and the
        // backend's RNS instance outlives the ViewModel (foreground
        // service keeps it up). Without this, a ViewModel destroyed on
        // Activity teardown stays pinned in RNS's handler list.
        // watchdogJob and clientEventJob don't need explicit cancellation
        // — they're launched in viewModelScope, which is cancelled
        // automatically after onCleared().
        announceHandlerRegistration?.deregister()
        announceHandlerRegistration = null
        // Don't disconnect/stop the backend here — the foreground service
        // keeps the RNS instance alive across ViewModel recreation, and
        // the next ViewModel's bringUpReticulum() resyncs to it.
    }
}
