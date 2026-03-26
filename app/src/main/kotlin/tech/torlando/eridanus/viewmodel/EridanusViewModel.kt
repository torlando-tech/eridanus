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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import network.reticulum.Reticulum
import network.reticulum.identity.Identity
import network.reticulum.interfaces.InterfaceAdapter
import network.reticulum.interfaces.local.LocalClientInterface
import network.reticulum.transport.AnnounceHandler
import network.reticulum.transport.Transport
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
    }

    private val prefs = PreferencesManager(application)
    private val identityStore = IdentityStore(application)
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

    // Client state
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
            entities.map { DiscoveredHub(hash = it.hash, name = it.name, lastSeen = it.lastSeen) }
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
    private var hubIdentity: Identity? = null
    private var clientIdentity: Identity? = null

    init {
        initReticulum()
        observeNotificationStatus()
    }

    private fun initReticulum() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Start Reticulum in standalone mode first
                val reticulum = Reticulum.start()

                hubIdentity = identityStore.loadHubIdentity()
                    ?: Identity.create().also { identityStore.saveHubIdentity(it) }
                clientIdentity = identityStore.loadClientIdentity()
                    ?: Identity.create().also { identityStore.saveClientIdentity(it) }

                _reticulumStarted.value = true
                EridanusConnectionService.start(getApplication())

                // Now try to connect to shared instance manually (like Carina does)
                val connected = tryConnectSharedInstance()
                _connectedToSharedInstance.value = connected
                Log.i(TAG, "Reticulum started (shared instance: $connected)")

                registerAnnounceHandler()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Reticulum", e)
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

    fun retrySharedInstance() {
        viewModelScope.launch(Dispatchers.IO) {
            val connected = tryConnectSharedInstance()
            _connectedToSharedInstance.value = connected
            if (connected) {
                registerAnnounceHandler()
            }
            Log.i(TAG, "Retry shared instance: connected=$connected")
        }
    }

    private fun tryConnectSharedInstance(): Boolean {
        val port = 37428
        if (!Reticulum.isSharedInstanceRunning(port)) {
            Log.w(TAG, "No shared instance on port $port")
            return false
        }
        return try {
            val client = LocalClientInterface(
                name = "SharedInstanceClient",
                tcpPort = port,
            )
            client.start()
            Transport.registerInterface(InterfaceAdapter.getOrCreate(client))
            Log.i(TAG, "Connected to shared instance on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to shared instance", e)
            false
        }
    }

    private fun registerAnnounceHandler() {
        try {
            val handler = AnnounceHandler { destHash, _, appData ->
                handleHubAnnounce(destHash, appData)
                true
            }
            Transport.registerAnnounceHandler(handler)
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
            hubDao.upsert(
                HubEntity(
                    hexHash = hexHash,
                    hash = hashCopy,
                    name = hubName,
                    lastSeen = System.currentTimeMillis(),
                )
            )
        }
    }

    fun connectToHub(hubHash: ByteArray) {
        val id = clientIdentity ?: return
        _connectionError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                EridanusConnectionService.start(getApplication())
                val client = RrcClient(id, nickname = nickname.value.ifEmpty { null })
                rrcClient = client
                _clientState.value = tech.torlando.eridanus.rrc.ClientState.CONNECTING

                // Collect events
                launch {
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                EridanusConnectionService.start(getApplication())
                val client = RrcClient(clientId, nickname = nickname.value.ifEmpty { null })
                rrcClient = client
                _clientState.value = tech.torlando.eridanus.rrc.ClientState.CONNECTING

                launch {
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
                val hub = RrcHub(id, prefs.getHubName())
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
                Log.d(TAG, "Welcome: hubName=${event.hubName}")
                _clientState.value = tech.torlando.eridanus.rrc.ClientState.ACTIVE
                _connectedHubName.value = event.hubName
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
                Log.d(TAG, "NoticeReceived: room=${event.room} body='${event.body}'")
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
                        _availableRooms.value = rooms
                        _currentRoom.value?.let { displayRoom ->
                            addMessage(displayRoom, ChatMessage(nick = null, body = event.body, src = null, isNotice = true))
                        }
                        return
                    } else if (event.body == "No public rooms registered" ||
                               event.body == "no rooms"
                    ) {
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
        // Don't disconnect/stop here — the foreground service keeps the process alive.
        // If the service is not running (user manually disconnected), these are already null.
    }
}
