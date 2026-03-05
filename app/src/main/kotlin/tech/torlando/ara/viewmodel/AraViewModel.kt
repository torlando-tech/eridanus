package tech.torlando.ara.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.UnicodeString
import co.nstant.`in`.cbor.model.UnsignedInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import network.reticulum.Reticulum
import network.reticulum.identity.Identity
import network.reticulum.interfaces.InterfaceAdapter
import network.reticulum.interfaces.local.LocalClientInterface
import network.reticulum.transport.AnnounceHandler
import network.reticulum.transport.Transport
import tech.torlando.ara.data.DarkModeOption
import tech.torlando.ara.data.IdentityStore
import tech.torlando.ara.data.PreferencesManager
import tech.torlando.ara.data.db.AraDatabase
import tech.torlando.ara.data.db.HubEntity
import tech.torlando.ara.rrc.RrcClient
import tech.torlando.ara.rrc.RrcConstants
import tech.torlando.ara.rrc.RrcEvent
import tech.torlando.ara.rrc.RrcHub
import tech.torlando.ara.ui.theme.PresetTheme
import java.io.ByteArrayInputStream

data class DiscoveredHub(
    val hash: ByteArray,
    val name: String,
    val lastSeen: Long = System.currentTimeMillis(),
) {
    val hexHash: String get() = hash.joinToString("") { "%02x".format(it) }
}

data class ChatMessage(
    val nick: String?,
    val body: String,
    val src: ByteArray?,
    val timestamp: Long = System.currentTimeMillis(),
    val isNotice: Boolean = false,
    val isError: Boolean = false,
)

class AraViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AraViewModel"
    }

    private val prefs = PreferencesManager(application)
    private val identityStore = IdentityStore(application)
    private val hubDao = AraDatabase.getInstance(application).hubDao()

    val theme: StateFlow<PresetTheme> = prefs.theme.stateIn(
        viewModelScope, SharingStarted.Eagerly, PresetTheme.ARA
    )

    val darkMode: StateFlow<DarkModeOption> = prefs.darkMode.stateIn(
        viewModelScope, SharingStarted.Eagerly, DarkModeOption.SYSTEM
    )

    val nickname: StateFlow<String> = prefs.nickname.stateIn(
        viewModelScope, SharingStarted.Eagerly, ""
    )

    val hubName: StateFlow<String> = prefs.hubName.stateIn(
        viewModelScope, SharingStarted.Eagerly, "Ara Hub"
    )

    // Reticulum state
    private val _reticulumStarted = MutableStateFlow(false)
    val reticulumStarted: StateFlow<Boolean> = _reticulumStarted

    private val _connectedToSharedInstance = MutableStateFlow(false)
    val connectedToSharedInstance: StateFlow<Boolean> = _connectedToSharedInstance

    // Client state
    private var rrcClient: RrcClient? = null
    private val _clientState = MutableStateFlow(tech.torlando.ara.rrc.ClientState.DISCONNECTED)
    val clientState: StateFlow<tech.torlando.ara.rrc.ClientState> = _clientState

    private val _connectedHubName = MutableStateFlow<String?>(null)
    val connectedHubName: StateFlow<String?> = _connectedHubName

    private val _joinedRooms = MutableStateFlow<Set<String>>(emptySet())
    val joinedRooms: StateFlow<Set<String>> = _joinedRooms

    private val _currentRoom = MutableStateFlow<String?>(null)
    val currentRoom: StateFlow<String?> = _currentRoom

    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts

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

    // Identities
    private var hubIdentity: Identity? = null
    private var clientIdentity: Identity? = null

    init {
        initReticulum()
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = RrcClient(id, nickname = nickname.value.ifEmpty { null })
                rrcClient = client
                _clientState.value = tech.torlando.ara.rrc.ClientState.CONNECTING

                // Collect events
                launch {
                    client.events.collect { event ->
                        handleRrcEvent(event)
                    }
                }

                client.connect(hubHash)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to hub", e)
                _clientState.value = tech.torlando.ara.rrc.ClientState.DISCONNECTED
            }
        }
    }

    fun disconnectFromHub() {
        viewModelScope.launch(Dispatchers.IO) {
            rrcClient?.disconnect()
            rrcClient = null
            _clientState.value = tech.torlando.ara.rrc.ClientState.DISCONNECTED
            _connectedHubName.value = null
            _joinedRooms.value = emptySet()
            _currentRoom.value = null
            _messages.value = emptyMap()
            _unreadCounts.value = emptyMap()
        }
    }

    fun joinRoom(room: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rrcClient?.join(room)
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

    fun sendMessage(text: String) {
        val room = _currentRoom.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rrcClient?.sendMessage(room, text)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
            }
        }
    }

    fun setCurrentRoom(room: String?) {
        _currentRoom.value = room
        if (room != null) {
            val counts = _unreadCounts.value.toMutableMap()
            counts.remove(room)
            _unreadCounts.value = counts
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = RrcClient(clientId, nickname = nickname.value.ifEmpty { null })
                rrcClient = client
                _clientState.value = tech.torlando.ara.rrc.ClientState.CONNECTING

                launch {
                    client.events.collect { event ->
                        handleRrcEvent(event)
                    }
                }

                client.connect(hash, knownIdentity = hubId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to own hub", e)
                _clientState.value = tech.torlando.ara.rrc.ClientState.DISCONNECTED
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
                val hub = RrcHub(id, hubName.value)
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
                _clientState.value = tech.torlando.ara.rrc.ClientState.ACTIVE
                _connectedHubName.value = event.hubName
            }

            is RrcEvent.Joined -> {
                val rooms = _joinedRooms.value.toMutableSet()
                rooms.add(event.room)
                _joinedRooms.value = rooms
                if (_currentRoom.value == null) {
                    _currentRoom.value = event.room
                }
                addMessage(event.room, ChatMessage(
                    nick = null,
                    body = "Joined #${event.room}",
                    src = null,
                    isNotice = true,
                ))
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

            is RrcEvent.Disconnected -> {
                _clientState.value = tech.torlando.ara.rrc.ClientState.DISCONNECTED
                _connectedHubName.value = null
                _joinedRooms.value = emptySet()
            }

            is RrcEvent.ConnectionFailed -> {
                _clientState.value = tech.torlando.ara.rrc.ClientState.DISCONNECTED
            }
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
        rrcClient?.disconnect()
        rrcHub?.stop()
    }
}
