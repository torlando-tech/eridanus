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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
import tech.torlando.eridanus.util.Base32
import tech.torlando.eridanus.rrc.RrcHub
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
    // IRC-style action/emote (T_ACTION); rendered "* nick body".
    val isAction: Boolean = false,
    // For join/part notices: the affected member's full destination hash.
    // Lets the renderer resolve a *current* nick from the room's member list
    // at display time (and re-resolve on member-list updates), so a join
    // notice that initially showed a hash retroactively gains a nickname
    // once we learn it via a message or /who response. Body holds just the
    // verb ("joined" / "left") in that case; the renderer prepends the
    // actor — see MessageItem in ChatScreen.
    val memberHash: ByteArray? = null,
    // True only for *other* members' join/part notices (set at the
    // MemberJoined/MemberParted handlers). Lets the chat view hide them from
    // the scrollback and flash them ephemerally when the "hide join/part"
    // setting is on. Self join/leave and other system notices stay false.
    val isJoinPart: Boolean = false,
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

        // How long [establishHubConnection] waits for the hub's WELCOME
        // before declaring the attempt failed. Mirrors the 30s ceiling the
        // original fire-and-forget connect path used.
        private const val HUB_CONNECT_TIMEOUT_MS = 30_000L

        // Auto-reconnect backoff: first retry after BASE, doubling up to MAX.
        // The loop runs as long as the user still intends to be connected
        // (see [reconnectHubHash] / [intentionalDisconnect]), so a slow
        // shared-instance host restart just means a few extra cycles.
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L

        // While waiting for the shared-instance host to come back during a
        // reconnect, poll its presence on this cadence. Tighter than the
        // 10s watchdog so recovery feels responsive once we already know the
        // link dropped.
        private const val RECONNECT_SHARED_INSTANCE_POLL_MS = 1_000L

        // Upper bound on how long [awaitSharedInstance] blocks before
        // attempting the hub connect anyway. The python LocalClientInterface
        // can silently self-heal its socket without our attach flag ever
        // flipping, so we must not wait on the flag forever.
        private const val RECONNECT_SHARED_INSTANCE_MAX_WAIT_MS = 30_000L
    }

    private val backend: RnsBackend = (application as EridanusApp).rnsBackend
    /** "kotlin" or "python" — surfaces in the SharedInstanceBannerCard so
     * trust-skeptical users on the python flavor can confirm the reference
     * stack is what's running. */
    val backendIdentifier: String = backend.identifier
    /** Version of the embedded Reticulum stack, shown in the About card. */
    val reticulumVersion: String = backend.reticulumVersion
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

    // When true, other members' join/part notices are hidden from the room
    // scrollback and shown ephemerally instead (see joinPartNotices).
    val hideJoinPart: StateFlow<Boolean> = prefs.hideJoinPart.stateIn(
        viewModelScope, SharingStarted.Eagerly, false
    )

    fun setHideJoinPart(enabled: Boolean) {
        viewModelScope.launch { prefs.setHideJoinPart(enabled) }
    }

    // Fires (room, message) for every other-member join/part as it arrives, so
    // the chat view can flash it ephemerally while hideJoinPart is on. The
    // notice is *also* recorded in _messages, so toggling hideJoinPart off
    // reveals the full history — this stream is purely for the live flash.
    private val _joinPartNotices = MutableSharedFlow<Pair<String, ChatMessage>>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val joinPartNotices: SharedFlow<Pair<String, ChatMessage>> = _joinPartNotices

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

    // ── Hub auto-reconnect intent ──────────────────────────────────────
    // The hub link is bound to the backend's Reticulum instance. When the
    // shared-instance host (Sideband / Columba / rnsd) restarts, RNS runs
    // Transport.shared_connection_disappeared(), which tears down every
    // active link — our hub link included — and the closedCallback fires
    // RrcEvent.Disconnected. Nothing rebuilds that link on its own, so the
    // user silently falls out of the hub and every room. These fields let
    // [scheduleHubReconnect] re-establish the session transparently:
    //
    // - reconnectHubHash: the hub to reconnect to. Set on connectToHub,
    //   cleared on user-driven disconnect/shutdown. Non-null == "the user
    //   wants to be connected", which is what distinguishes a dropped link
    //   (reconnect) from a deliberate leave (don't).
    // - intentionalDisconnect: guards the brief window where we tear the
    //   link down ourselves (disconnect/shutdown/identity swap) and the
    //   resulting Disconnected event must NOT trigger a reconnect.
    // - sessionRoomKeys: room (lowercased) -> +k key, so keyed rooms can be
    //   silently re-joined. In-memory only, for the session's lifetime.
    // - reconnectJob: the single in-flight reconnect loop (guarded so a
    //   flurry of Disconnected events can't spawn duplicates).
    private var reconnectHubHash: ByteArray? = null
    @Volatile private var intentionalDisconnect = false
    private val sessionRoomKeys = mutableMapOf<String, String?>()
    private var roomsToRejoin: List<String> = emptyList()
    private var reconnectJob: Job? = null

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

    val keepConnectionAlive: StateFlow<Boolean> = prefs.keepConnectionAlive.stateIn(
        viewModelScope, SharingStarted.Eagerly, false
    )

    // Identities
    private var hubIdentity: RnsIdentity? = null
    private var clientIdentity: RnsIdentity? = null

    /** Hex hash of the active client identity, surfaced to the IdentityCard
     *  so the user can verify their address. Updated whenever clientIdentity
     *  is loaded or replaced (import). null while uninitialized. */
    private val _clientIdentityHashHex = MutableStateFlow<String?>(null)
    val clientIdentityHashHex: StateFlow<String?> = _clientIdentityHashHex

    /** One-shot results from [importClientIdentity] consumed by the
     *  IdentityCard. SharedFlow (not StateFlow) so the same outcome doesn't
     *  re-fire on recomposition. */
    private val _identityImportResult = MutableSharedFlow<IdentityImportResult>(extraBufferCapacity = 1)
    val identityImportResult: SharedFlow<IdentityImportResult> = _identityImportResult

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
        observeKeepAliveWakeLock()
    }

    private fun initReticulum() {
        viewModelScope.launch(Dispatchers.IO) {
            bringUpReticulum()
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
        _clientIdentityHashHex.value = clientIdentity?.hash?.toHex()
    }

    // ── Identity import/export ─────────────────────────────────────────
    // File and text formats are byte-identical to Sideband's: the Base32-
    // encoded text and the raw file are both the 64-byte RNS Identity
    // private key (X25519 prv [32] + Ed25519 sig_prv [32]) — same shape
    // produced by `RNS.Identity.get_private_key()` / consumed by
    // `RNS.Identity.from_bytes`, which both flavors' RnsIdentityFactory
    // already mirrors. See Sideband's sbapp/ui/keys.py.

    /** Base32-encoded private key for share-sheet / clipboard handoff to
     *  Sideband or another LXMF-style client. Null if no identity yet. */
    fun exportClientIdentityBase32(): String? =
        clientIdentity?.getPrivateKey()?.let { Base32.encode(it) }

    /** Raw 64-byte private key for file export (`Identity.to_file` shape). */
    fun exportClientIdentityBytes(): ByteArray? = clientIdentity?.getPrivateKey()

    /** Decode a Base32 blob (e.g. pasted from Sideband) and import. Emits a
     *  result on [identityImportResult]. */
    fun importClientIdentityFromBase32(text: String) {
        val bytes = try {
            Base32.decode(text.trim())
        } catch (e: Throwable) {
            viewModelScope.launch {
                _identityImportResult.emit(
                    IdentityImportResult.Failure("Invalid Base32 key: ${e.message ?: "decode failed"}"),
                )
            }
            return
        }
        importClientIdentity(bytes)
    }

    /** Validate, swap, and persist a 64-byte RNS identity private key as the
     *  new client identity. In-process: drops any active hub link so the
     *  next connect builds an RrcClient with the new identity — no app
     *  restart needed. Emits success/failure on [identityImportResult]. */
    fun importClientIdentity(privateKeyBytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                require(privateKeyBytes.size == 64) {
                    "Identity key must be exactly 64 bytes (got ${privateKeyBytes.size})"
                }
                val newIdentity = backend.identities.fromBytes(privateKeyBytes)
                    ?: error("Reticulum rejected the key data")

                // RrcClient was constructed bound to the old identity. Tear
                // it down so any subsequent connectToHub() builds a fresh
                // client with the new identity. Clear the reconnect intent
                // too — we don't want an armed auto-reconnect silently
                // re-attaching the old session under the new identity.
                intentionalDisconnect = true
                reconnectHubHash = null
                reconnectJob?.cancel()
                reconnectJob = null
                sessionRoomKeys.clear()
                roomsToRejoin = emptyList()
                clientEventJob?.cancel()
                rrcClient?.disconnect()
                rrcClient = null
                _clientState.value = tech.torlando.eridanus.rrc.ClientState.DISCONNECTED
                _connectedHubName.value = null
                _joinedRooms.value = emptySet()
                _currentRoom.value = null
                _messages.value = emptyMap()
                _unreadCounts.value = emptyMap()
                _availableRooms.value = emptyList()

                clientIdentity = newIdentity
                identityStore.saveClientIdentity(newIdentity)
                _clientIdentityHashHex.value = newIdentity.hash.toHex()

                Log.i(TAG, "Client identity imported (hash=${newIdentity.hash.toHex().take(16)}…)")
                _identityImportResult.emit(IdentityImportResult.Success)
            } catch (e: Throwable) {
                Log.w(TAG, "Identity import failed: ${e.message}")
                _identityImportResult.emit(
                    IdentityImportResult.Failure(e.message ?: "Import failed"),
                )
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

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
                // A backend restart re-attaches transport but leaves the hub
                // link dead. If the user still intends to be connected and we
                // aren't already (re)connecting, drive a reconnect now. This
                // covers the case where the link died without firing a
                // closedCallback (so no Disconnected event armed the loop)
                // and the watchdog-triggered restart is the first signal.
                if (reconnectHubHash != null && !intentionalDisconnect &&
                    _clientState.value != tech.torlando.eridanus.rrc.ClientState.ACTIVE
                ) {
                    scheduleHubReconnect()
                }
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
                // The RNS-hosting foreground service owns the app's single
                // persistent notification — push the status line into it.
                backend.setForegroundStatus(text)
            }
        }
    }

    /**
     * Hold a partial wake lock (via the backend's hosting service) only while
     * the user's "keep connection alive in background" setting is on *and*
     * we're actually connected to a hub. That's the one state where Doze
     * suspending the CPU would silently drop the link; holding the lock any
     * other time is pure battery waste. distinctUntilChanged collapses the
     * combine's churn so the lock is only toggled on real transitions.
     */
    private fun observeKeepAliveWakeLock() {
        viewModelScope.launch {
            combine(keepConnectionAlive, _clientState) { enabled, state ->
                enabled && state == tech.torlando.eridanus.rrc.ClientState.ACTIVE
            }.distinctUntilChanged().collect { shouldHold ->
                backend.setKeepAliveWakeLock(shouldHold)
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

    /**
     * User-driven full shutdown — drop any hub link, stop the hosted hub,
     * tear the backend's foreground service down, then hand back to the UI
     * to finish the Activity and kill the process. After this returns
     * Eridanus has zero background presence: no notification, no service,
     * no announce listener.
     *
     * Async so we can wait briefly for the backend service to actually
     * exit before the caller kills the process — that window is what lets
     * PyReticulumService.onDestroy run RNS.exit_handler cleanly on the
     * python flavor, and rns-android's StoreLifecycle.drain flush its
     * announce-cache on the kotlin flavor. [onComplete] runs on the main
     * thread after teardown (or after a 3s timeout, whichever first); it
     * should call `activity.finishAndRemoveTask()` and
     * `android.os.Process.killProcess(myPid())`.
     */
    fun fullShutdown(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.i(TAG, "User-requested full shutdown")
            try {
                // Cancel the watchdog first so it can't see the about-to-
                // disappear shared-instance host and kick off a restart
                // mid-teardown.
                watchdogJob?.cancel()
                watchdogJob = null

                // Clear the reconnect intent and kill any in-flight
                // reconnect loop so the link teardown below can't trigger a
                // doomed auto-reconnect against the dying backend.
                intentionalDisconnect = true
                reconnectHubHash = null
                reconnectJob?.cancel()
                reconnectJob = null
                sessionRoomKeys.clear()
                roomsToRejoin = emptyList()

                // Drop the hub link and any hosted hub before the service
                // dies, so each end has a chance to send its close packet
                // rather than just vanishing on the wire.
                clientEventJob?.cancel()
                clientEventJob = null
                rrcClient?.disconnect()
                rrcClient = null
                rrcHub?.stop()
                rrcHub = null
                _clientState.value = tech.torlando.eridanus.rrc.ClientState.DISCONNECTED
                _hubRunning.value = false
                _hubClients.value = 0
                _hubDestHash.value = null

                // Deregister our announce handler so the RNS instance
                // about to be torn down doesn't dispatch one last announce
                // through us during its own shutdown.
                announceHandlerRegistration?.deregister()
                announceHandlerRegistration = null

                // Stop the backend foreground service. PyReticulumService.
                // onDestroy runs RNS.Reticulum.exit_handler; rns-android's
                // ReticulumService runs its own StoreLifecycle.drain.
                backend.stop(getApplication())

                // Wait briefly for the service to actually fall over before
                // returning to the caller (which will killProcess). Same
                // 3s ceiling the backends' own restart() paths use as a
                // teardown budget.
                val deadline = System.currentTimeMillis() + 3_000L
                while (System.currentTimeMillis() < deadline && backend.isRunning) {
                    delay(100)
                }
                _reticulumStarted.value = false
                _connectedToSharedInstance.value = false
                Log.i(TAG, "Shutdown complete (backend running=${backend.isRunning})")
            } catch (e: Exception) {
                Log.e(TAG, "Error during full shutdown", e)
            } finally {
                // Hop back to the main thread for the activity finish +
                // killProcess — finishAndRemoveTask must be called on the
                // UI thread.
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onComplete()
                }
            }
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
            // Scope delivery to rrc.hub announces only. Without an aspect
            // filter the backend hands us EVERY announce on the network, and
            // decoding foreign (non-RRC) app_data as CBOR can drive the
            // decoder into a multi-GB allocation off a bogus length prefix.
            announceHandlerRegistration =
                backend.transport.registerAnnounceHandler(RrcConstants.DEST_NAME, handler)
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
        if (clientIdentity == null) return
        // Fresh user-driven connect: (re)arm the reconnect intent and drop
        // any stale rejoin state from a previous hub.
        intentionalDisconnect = false
        reconnectHubHash = hubHash.copyOf()
        sessionRoomKeys.clear()
        roomsToRejoin = emptyList()
        val prevReconnect = reconnectJob
        reconnectJob = null
        viewModelScope.launch(Dispatchers.IO) {
            // Fully stop any in-flight auto-reconnect loop before we build
            // the new connection, so the two don't race over rrcClient.
            prevReconnect?.cancelAndJoin()
            establishHubConnection(hubHash)
        }
    }

    /**
     * Build a fresh [RrcClient], connect it to [hubHash], and suspend until
     * the hub sends WELCOME (state ACTIVE) or the attempt fails / times out.
     * Returns true only on a fully established session. Shared by the
     * user-driven [connectToHub] and the [scheduleHubReconnect] loop so both
     * paths have identical setup/teardown semantics.
     */
    private suspend fun establishHubConnection(hubHash: ByteArray): Boolean {
        val id = clientIdentity ?: return false
        _connectionError.value = null
        // Stop collecting the old client's events and tear its (dead) link
        // down before replacing it, so a stale closedCallback can't race the
        // new connection's state. cancelAndJoin (not bare cancel) so no
        // buffered Disconnected from the old client lands after we proceed
        // and spuriously arms a second reconnect loop.
        clientEventJob?.cancelAndJoin()
        rrcClient?.disconnect()

        val client = RrcClient(id, backend, nickname = nickname.value.ifEmpty { null })
        rrcClient = client
        _clientState.value = tech.torlando.eridanus.rrc.ClientState.CONNECTING
        clientEventJob = viewModelScope.launch(Dispatchers.IO) {
            client.events.collect { event -> handleRrcEvent(event) }
        }

        return try {
            // connect() blocks on path lookup (~up to 17s) then establishes
            // the link asynchronously; WELCOME arrives as a packet and the
            // event handler flips us to ACTIVE. A failure emits
            // ConnectionFailed, whose handler flips us to DISCONNECTED.
            client.connect(hubHash)
            val outcome = withTimeoutOrNull(HUB_CONNECT_TIMEOUT_MS) {
                clientState.first {
                    it == tech.torlando.eridanus.rrc.ClientState.ACTIVE ||
                        it == tech.torlando.eridanus.rrc.ClientState.DISCONNECTED
                }
            }
            if (outcome == tech.torlando.eridanus.rrc.ClientState.ACTIVE) {
                true
            } else {
                // Timed out still in CONNECTING/AWAITING_WELCOME (outcome
                // null), or a failure event already moved us to DISCONNECTED.
                if (_clientState.value != tech.torlando.eridanus.rrc.ClientState.ACTIVE) {
                    client.disconnect()
                    if (rrcClient === client) rrcClient = null
                    _clientState.value = tech.torlando.eridanus.rrc.ClientState.DISCONNECTED
                    if (_connectionError.value == null) {
                        _connectionError.value = "Connection timed out"
                    }
                }
                false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to hub", e)
            _clientState.value = tech.torlando.eridanus.rrc.ClientState.DISCONNECTED
            false
        }
    }

    /**
     * Re-establish the hub link (and re-join the rooms we were in) after an
     * unexpected drop — typically the shared-instance host restarting under
     * us. Single-flight: a second Disconnected event while a loop is already
     * running is a no-op. Runs with exponential backoff for as long as the
     * user still intends to be connected; [disconnectFromHub] /
     * [fullShutdown] clear that intent and cancel the loop.
     */
    private fun scheduleHubReconnect() {
        if (intentionalDisconnect) return
        if (reconnectJob?.isActive == true) return
        val hubHash = reconnectHubHash ?: return
        reconnectJob = viewModelScope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive && !intentionalDisconnect && reconnectHubHash != null) {
                // Don't bother trying to build a link until transport is
                // actually back — otherwise every attempt burns the full
                // path-lookup timeout failing.
                awaitSharedInstance()
                if (!isActive || intentionalDisconnect) break

                attempt++
                Log.i(TAG, "Hub auto-reconnect: attempt $attempt")
                val ok = establishHubConnection(hubHash)
                if (ok) {
                    Log.i(
                        TAG,
                        "Hub auto-reconnect: re-established after $attempt attempt(s); " +
                            "rejoining ${roomsToRejoin.size} room(s)",
                    )
                    rejoinRooms()
                    return@launch
                }
                if (intentionalDisconnect) break

                // 2s, 4s, 8s, 16s, then capped at 30s.
                val backoff = (RECONNECT_BASE_DELAY_MS shl (attempt - 1).coerceAtMost(4))
                    .coerceAtMost(RECONNECT_MAX_DELAY_MS)
                Log.i(TAG, "Hub auto-reconnect: attempt $attempt failed, retrying in ${backoff}ms")
                delay(backoff)
            }
            Log.i(TAG, "Hub auto-reconnect: loop ended (intentional=$intentionalDisconnect)")
        }
    }

    /**
     * Block until the shared-instance host is back (or a bounded ceiling
     * elapses). If the host is up but our attach flag says we're detached —
     * the watchdog hasn't caught it yet — nudge a [restartBackend] so we
     * re-attach promptly instead of waiting out the 10s watchdog cadence.
     *
     * Bounded so we still attempt the connect even when the attach flag
     * never flips (the python LocalClientInterface can self-heal its socket
     * silently); a failed attempt just backs off and tries again.
     */
    private suspend fun awaitSharedInstance() {
        if (_connectedToSharedInstance.value) return
        val deadline = RECONNECT_SHARED_INSTANCE_MAX_WAIT_MS / RECONNECT_SHARED_INSTANCE_POLL_MS
        var ticks = 0L
        while (currentCoroutineContext().isActive && !intentionalDisconnect &&
            !_connectedToSharedInstance.value
        ) {
            val hostUp = try {
                backend.isSharedInstanceRunning()
            } catch (e: Exception) {
                false
            }
            if (hostUp && !_isRestarting.value) {
                // Host is bound but we're not attached — re-run the attach
                // probe. restartBackend() is mutex-guarded, so this can't
                // collide with a concurrent watchdog restart.
                restartBackend()
                if (_connectedToSharedInstance.value) return
            }
            if (++ticks >= deadline) return
            delay(RECONNECT_SHARED_INSTANCE_POLL_MS)
        }
    }

    /**
     * Silently re-join every room we were in before the drop, using the
     * keys captured in [sessionRoomKeys] for +k rooms. Best-effort: a room
     * that now rejects us (banned, bad key) just stays un-joined.
     */
    private fun rejoinRooms() {
        val rooms = roomsToRejoin
        if (rooms.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (room in rooms) {
                if (intentionalDisconnect) break
                try {
                    rrcClient?.join(room, key = sessionRoomKeys[room])
                    // Gentle pacing so a multi-room rejoin doesn't burst the
                    // freshly re-established link.
                    delay(150)
                } catch (e: Exception) {
                    Log.w(TAG, "Rejoin failed for #$room: ${e.message}")
                }
            }
        }
    }

    fun clearConnectionError() {
        _connectionError.value = null
    }

    fun disconnectFromHub() {
        // User-driven leave: clear the reconnect intent and kill any
        // in-flight reconnect loop so the Disconnected event below doesn't
        // bounce us straight back in.
        intentionalDisconnect = true
        reconnectHubHash = null
        reconnectJob?.cancel()
        reconnectJob = null
        sessionRoomKeys.clear()
        roomsToRejoin = emptyList()
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
        }
    }

    fun joinRoom(room: String, key: String? = null) {
        val r = room.trim().lowercase()
        // Remember the key (if any) so an auto-reconnect can silently
        // re-join this room. Keyed by the lowercased name to match the
        // form the JOINED fanout reports back in _joinedRooms.
        sessionRoomKeys[r] = key?.ifEmpty { null }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rrcClient?.join(r, key = key?.ifEmpty { null })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to join room", e)
            }
        }
    }

    fun partRoom(room: String) {
        val r = room.trim().lowercase()
        // Deliberate leave: drop the rejoin intent for this room so a later
        // reconnect doesn't drag the user back into a room they left.
        sessionRoomKeys.remove(r)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rrcClient?.part(r)
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
            // /me is an action, not a hub command: send it as T_ACTION room
            // content rather than letting it fall through to sendCommand
            // (which the hub would reject as an unrecognized command).
            if (cmd == "/me") {
                if (rest.isBlank()) return
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        rrcClient?.sendAction(room, rest)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send action", e)
                    }
                }
                return
            }
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
/me <action> — send an action/emote
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

    fun setKeepConnectionAlive(enabled: Boolean) {
        viewModelScope.launch { prefs.setKeepConnectionAlive(enabled) }
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
        val hubId = hubIdentity ?: run {
            Log.w(TAG, "connectToOwnHub: no hub identity")
            return
        }
        val clientId = clientIdentity ?: run {
            Log.w(TAG, "connectToOwnHub: no client identity")
            return
        }
        // The hub lives in this same process, so a real RNS link to it would
        // have to round-trip through the external shared-instance host, which
        // mis-routes the loopback (the hub's WELCOME comes back to the hub,
        // not the client). Wire client↔hub in-process instead. Requires the
        // hub to be running — which it is whenever the Connect affordance is
        // shown. See LoopbackLink and a645cf5 (the "never host" switch that
        // turned own-hub links from in-process loopbacks into host round-trips).
        val hub = rrcHub ?: run {
            Log.w(TAG, "connectToOwnHub: hub not running")
            return
        }
        Log.i(TAG, "connectToOwnHub: wiring in-process loopback to local hub")
        _connectionError.value = null
        // Disarm any remote-hub auto-reconnect intent left over from a prior
        // session. The own-hub link is an in-process loopback with its own
        // lifecycle (tied to the hosted hub), so it must not fall into the
        // remote reconnect loop — otherwise a loopback teardown would try to
        // re-attach to whatever remote hub we last visited.
        reconnectHubHash = null
        reconnectJob?.cancel()
        reconnectJob = null
        sessionRoomKeys.clear()
        roomsToRejoin = emptyList()
        clientEventJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = RrcClient(clientId, backend, nickname = nickname.value.ifEmpty { null })
                rrcClient = client
                _clientState.value = tech.torlando.eridanus.rrc.ClientState.CONNECTING

                clientEventJob = launch {
                    client.events.collect { event ->
                        handleRrcEvent(event)
                    }
                }

                val (clientEnd, hubEnd) = tech.torlando.eridanus.rrc.LoopbackLink.pair(
                    clientIdentity = clientId,
                    hubIdentity = hubId,
                )
                hub.acceptLoopbackClient(hubEnd)
                client.connectViaLink(clientEnd)
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

    /** Removes a discovered hub from the list. It will reappear if the hub
     *  announces again. */
    fun removeHub(hexHash: String) {
        Log.i(TAG, "removeHub: $hexHash")
        viewModelScope.launch(Dispatchers.IO) {
            hubDao.delete(hexHash)
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
                    // Seed the member list from the join roster so the member
                    // sheet matches the count immediately. These are hashes
                    // only; the auto-requested /who below enriches them with
                    // nicks when its notice returns.
                    val memberList = _roomMemberList.value.toMutableMap()
                    memberList[event.room] = event.members.map {
                        RoomMember(nick = null, hashPrefix = it.toHex().take(12))
                    }
                    _roomMemberList.value = memberList
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
                    isAction = event.isAction,
                ))
                // Opportunistically backfill the sender's nick into the
                // room's member list. Without this, a peer who joined
                // before our last /who is in _roomMemberList with
                // nick=null, and a structured join notice for them
                // (memberHash-resolved at render time) can never gain a
                // name no matter how much they talk.
                if (event.src != null && !event.nick.isNullOrEmpty()) {
                    val prefix = event.src.toHex().take(12)
                    val memberList = _roomMemberList.value.toMutableMap()
                    val list = memberList[event.room]
                    if (list != null) {
                        var changed = false
                        val updated = list.map { m ->
                            if (m.hashPrefix == prefix && m.nick != event.nick) {
                                changed = true
                                m.copy(nick = event.nick)
                            } else m
                        }
                        if (changed) {
                            memberList[event.room] = updated
                            _roomMemberList.value = memberList
                        }
                    }
                }
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
                        // A roomless "/who" response still names its room in the
                        // body ("members in X: ..."), so parse it before falling
                        // back to treating the text as a hub greeting.
                        if (parseMembersNotice(event.body)) return
                        // Roomless notice that isn't /list — treat as hub greeting
                        _hubGreetingMessage.value = event.body
                        return
                    }
                }
                // Parse topic notices
                parseTopicNotice(event.body)
                // Parse /who response for member list/count. This also
                // displays the "members in #room: ..." line inline (below),
                // which is the user-visible result of a manual /who.
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
                // Body holds just the verb; MessageItem prepends the actor
                // by looking memberHash up in the room's member list at
                // render time. Seeding the advisory nick (event.nick, from
                // the hub's K_NICK on the JOINED fanout) into the list below
                // makes the notice render "<nick> joined" on first paint.
                // When the hub doesn't send a nick (older hub), it stays a
                // short hash until we learn the nick from the joiner's first
                // message (MessageReceived backfill) — the notice re-renders
                // then, since it resolves the member list at display time.
                val joinMsg = ChatMessage(
                    nick = null,
                    body = "joined",
                    src = null,
                    isNotice = true,
                    memberHash = event.memberHash,
                    isJoinPart = true,
                )
                addMessage(event.room, joinMsg)
                _joinPartNotices.tryEmit(event.room to joinMsg)
                val counts = _roomMemberCounts.value.toMutableMap()
                counts[event.room] = (counts[event.room] ?: 0) + 1
                _roomMemberCounts.value = counts
                val prefix = event.memberHash.toHex().take(12)
                val memberList = _roomMemberList.value.toMutableMap()
                val existing = memberList[event.room] ?: emptyList()
                val idx = existing.indexOfFirst { it.hashPrefix == prefix }
                if (idx < 0) {
                    memberList[event.room] = existing + RoomMember(nick = event.nick, hashPrefix = prefix)
                    _roomMemberList.value = memberList
                } else if (!event.nick.isNullOrEmpty() && existing[idx].nick != event.nick) {
                    memberList[event.room] = existing.toMutableList().also {
                        it[idx] = it[idx].copy(nick = event.nick)
                    }
                    _roomMemberList.value = memberList
                }
            }

            is RrcEvent.MemberParted -> {
                // Unlike MemberJoined, PART resolves the actor *now* and
                // bakes it into the body string. We remove the member from
                // _roomMemberList in this same handler, so a render-time
                // lookup would lose them — and their nick is never going
                // to update again once they've left, so there's no value
                // in keeping the structured form around either.
                // Prefer the advisory nick the hub attaches to the PARTED
                // fanout (event.nick), then a nick we already had cached,
                // then the short hash.
                val prefix = event.memberHash.toHex().take(12)
                val knownNick = _roomMemberList.value[event.room]
                    ?.firstOrNull { it.hashPrefix == prefix }?.nick
                val shortHash = event.memberHash.take(6).joinToString("") { "%02x".format(it) }
                val actor = event.nick?.takeIf { it.isNotEmpty() } ?: knownNick ?: shortHash
                val partMsg = ChatMessage(
                    nick = null,
                    body = "$actor left",
                    src = null,
                    isNotice = true,
                    isJoinPart = true,
                )
                addMessage(event.room, partMsg)
                _joinPartNotices.tryEmit(event.room to partMsg)
                val counts = _roomMemberCounts.value.toMutableMap()
                val current = counts[event.room] ?: 0
                if (current > 0) counts[event.room] = current - 1
                _roomMemberCounts.value = counts
                val memberList = _roomMemberList.value.toMutableMap()
                memberList[event.room]?.let { list ->
                    memberList[event.room] = list.filterNot { it.hashPrefix == prefix }
                    _roomMemberList.value = memberList
                }
            }

            is RrcEvent.Disconnected -> {
                val willReconnect = !intentionalDisconnect && reconnectHubHash != null
                if (willReconnect) {
                    // Unexpected drop (shared-instance host restarted, link
                    // torn down). Remember the rooms so we can re-join them,
                    // but DON'T wipe _joinedRooms / _currentRoom / _messages
                    // — keeping them avoids a flicker, the rejoin is
                    // idempotent against the list, and it means _joinedRooms
                    // still reflects the live set on a re-drop mid-reconnect.
                    // Show CONNECTING so the UI reads as "reconnecting"
                    // rather than dead.
                    roomsToRejoin = _joinedRooms.value.toList()
                    Log.i(
                        TAG,
                        "Link dropped; scheduling auto-reconnect " +
                            "(${roomsToRejoin.size} room(s) to rejoin)",
                    )
                    _clientState.value = tech.torlando.eridanus.rrc.ClientState.CONNECTING
                    // Member rosters are stale until we re-handshake; clear
                    // them so we don't show ghosts during the gap.
                    _roomMemberCounts.value = emptyMap()
                    _roomMemberList.value = emptyMap()
                    scheduleHubReconnect()
                } else {
                    Log.i(TAG, "Disconnected event received, clearing rooms")
                    _clientState.value = tech.torlando.eridanus.rrc.ClientState.DISCONNECTED
                    _connectedHubName.value = null
                    _joinedRooms.value = emptySet()
                    _availableRooms.value = emptyList()
                    _roomTopics.value = emptyMap()
                    _roomMemberCounts.value = emptyMap()
                    _roomMemberList.value = emptyMap()
                    _hubGreetingMessage.value = null
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

    /** Returns true if [body] was a "/who" member notice and was consumed. */
    private fun parseMembersNotice(body: String): Boolean {
        // "members in {room}: nick1 (hash1), nick2 (hash2)" or "(none)"
        val match = Regex("""^members in (\S+): (.+)$""").find(body) ?: return false
        // Normalize the room key the same way T_JOINED does (it lowercases),
        // otherwise the list lands under a key the member sheet never reads.
        val room = match.groupValues[1].trim().lowercase()
        val memberList = match.groupValues[2]
        if (memberList == "(none)") {
            val counts = _roomMemberCounts.value.toMutableMap()
            counts[room] = 0
            _roomMemberCounts.value = counts
            val members = _roomMemberList.value.toMutableMap()
            members[room] = emptyList()
            _roomMemberList.value = members
            return true
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
        return true
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

/** Outcome of [EridanusViewModel.importClientIdentity], consumed by the
 *  Identity card to show a toast/dialog. */
sealed class IdentityImportResult {
    object Success : IdentityImportResult()
    data class Failure(val message: String) : IdentityImportResult()
}
