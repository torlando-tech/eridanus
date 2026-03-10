package tech.torlando.ara.rrc

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.transport.Transport
import tech.torlando.ara.rrc.RrcConstants.B_WELCOME_CAPS
import tech.torlando.ara.rrc.RrcConstants.B_WELCOME_HUB
import tech.torlando.ara.rrc.RrcConstants.B_WELCOME_LIMITS
import tech.torlando.ara.rrc.RrcConstants.B_WELCOME_VER
import tech.torlando.ara.rrc.RrcConstants.DEST_NAME
import tech.torlando.ara.rrc.RrcConstants.K_BODY
import tech.torlando.ara.rrc.RrcConstants.K_NICK
import tech.torlando.ara.rrc.RrcConstants.K_ROOM
import tech.torlando.ara.rrc.RrcConstants.K_SRC
import tech.torlando.ara.rrc.RrcConstants.K_T
import tech.torlando.ara.rrc.RrcConstants.L_MAX_MSG_BODY_BYTES
import tech.torlando.ara.rrc.RrcConstants.L_MAX_NICK_BYTES
import tech.torlando.ara.rrc.RrcConstants.L_MAX_ROOMS_PER_SESSION
import tech.torlando.ara.rrc.RrcConstants.L_MAX_ROOM_NAME_BYTES
import tech.torlando.ara.rrc.RrcConstants.L_RATE_LIMIT_MSGS_PER_MINUTE
import tech.torlando.ara.rrc.RrcConstants.T_ERROR
import tech.torlando.ara.rrc.RrcConstants.T_HELLO
import tech.torlando.ara.rrc.RrcConstants.T_JOIN
import tech.torlando.ara.rrc.RrcConstants.T_JOINED
import tech.torlando.ara.rrc.RrcConstants.T_MSG
import tech.torlando.ara.rrc.RrcConstants.T_NOTICE
import tech.torlando.ara.rrc.RrcConstants.T_PART
import tech.torlando.ara.rrc.RrcConstants.T_PARTED
import tech.torlando.ara.rrc.RrcConstants.T_PING
import tech.torlando.ara.rrc.RrcConstants.T_PONG
import tech.torlando.ara.rrc.RrcConstants.T_WELCOME

data class HubSession(
    val link: Link,
    var welcomed: Boolean = false,
    var nick: String? = null,
    var peerHash: ByteArray? = null,
    val rooms: MutableSet<String> = mutableSetOf(),
)

class RrcHub(
    internal val identity: Identity,
    var hubName: String = "Ara Hub",
) {
    companion object {
        private const val TAG = "RrcHub"
    }

    // Hub configuration
    var greeting: String? = null
    var defaultRooms: List<tech.torlando.ara.data.DefaultRoomConfig> = emptyList()

    private var destination: Destination? = null
    private val sessions = mutableMapOf<Link, HubSession>()
    internal val roomManager = HubRoomManager()
    private val commandHandler = HubCommandHandler(this, roomManager)
    private var defaultRoomInitialized = false

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients: StateFlow<Int> = _connectedClients

    val destHash: ByteArray? get() = destination?.hash

    private var running = false
    private var announceJob: Job? = null

    fun start() {
        if (running) return

        val parsed = Destination.appAndAspectsFromName(DEST_NAME) ?: return
        val (appName, aspects) = parsed

        val dest = Destination.create(
            identity,
            DestinationDirection.IN,
            DestinationType.SINGLE,
            appName,
            *aspects.toTypedArray(),
        )

        Transport.registerDestination(dest)

        dest.setLinkEstablishedCallback { linkAny ->
            val link = linkAny as? Link ?: return@setLinkEstablishedCallback
            onLinkEstablished(link)
        }

        // Announce with app_data containing hub info as CBOR (string keys per rrcd convention)
        val announceData = RrcCodec.encodeStringKeyed(mapOf(
            "proto" to "rrc",
            "v" to 1,
            "hub" to hubName,
        ))
        dest.announce(announceData)

        destination = dest
        running = true
        initDefaultRoom()
        Log.i(TAG, "Hub started: $hubName (${dest.hexHash})")
    }

    fun announce() {
        val dest = destination ?: return
        val announceData = RrcCodec.encodeStringKeyed(mapOf(
            "proto" to "rrc",
            "v" to 1,
            "hub" to hubName,
        ))
        dest.announce(announceData)
        Log.i(TAG, "Hub announced: $hubName")
    }

    fun startAnnounceLoop(intervalSeconds: Int, scope: CoroutineScope) {
        stopAnnounceLoop()
        if (intervalSeconds <= 0) return
        announceJob = scope.launch {
            while (true) {
                delay(intervalSeconds * 1000L)
                if (!running) break
                announce()
            }
        }
        Log.i(TAG, "Announce loop started: every ${intervalSeconds}s")
    }

    fun stopAnnounceLoop() {
        announceJob?.cancel()
        announceJob = null
    }

    fun stop() {
        if (!running) return
        running = false
        stopAnnounceLoop()

        for ((link, _) in sessions) {
            try { link.teardown() } catch (_: Exception) {}
        }
        sessions.clear()
        roomManager.clear()
        defaultRoomInitialized = false
        _connectedClients.value = 0
        destination = null
        Log.i(TAG, "Hub stopped")
    }

    private fun initDefaultRoom() {
        if (defaultRoomInitialized) return
        defaultRoomInitialized = true
        if (defaultRooms.isEmpty()) return

        for (cfg in defaultRooms) {
            val room = cfg.name.trim().lowercase()
            if (room.isEmpty()) continue

            val st = roomManager.getOrCreateState(room)
            if (cfg.topic.isNotEmpty()) st.topic = cfg.topic

            val modes = cfg.modes
            if (modes.isNotEmpty()) {
                for (i in modes.indices) {
                    if (modes[i] == '+' || modes[i] == '-') continue
                    val isSet = i == 0 || modes[i - 1] != '-'
                    when (modes[i]) {
                        'n' -> st.noOutsideMsgs = isSet
                        't' -> st.topicOpsOnly = isSet
                        'm' -> st.moderated = isSet
                        'i' -> st.inviteOnly = isSet
                        'p' -> st.isPrivate = isSet
                        'k' -> st.key = if (isSet) cfg.key.ifEmpty { null } else null
                        'r' -> st.registered = isSet
                    }
                }
            } else {
                st.registered = true
            }

            Log.i(TAG, "Default room initialized: $room (modes=${roomManager.getModeString(room)}, topic=${st.topic})")
        }
    }

    // ── Internal accessors for command handler ─────────────────────────

    internal fun getSession(link: Link): HubSession? = sessions[link]

    internal fun allSessions(): List<Pair<Link, HubSession>> =
        sessions.entries.map { it.key to it.value }

    internal fun sendNotice(link: Link, room: String?, text: String) {
        val env = RrcEnvelope.make(T_NOTICE, src = identity.hash, room = room, body = text)
        sendPacket(link, env)
    }

    internal fun sendError(link: Link, text: String, room: String? = null) {
        val env = RrcEnvelope.make(T_ERROR, src = identity.hash, room = room, body = text)
        sendPacket(link, env)
    }

    internal fun updateNickIndex(@Suppress("UNUSED_PARAMETER") link: Link, @Suppress("UNUSED_PARAMETER") oldNick: String?, @Suppress("UNUSED_PARAMETER") newNick: String) {
        // Nick tracked on session; command handler iterates sessions for lookup
    }

    // ── Link lifecycle ─────────────────────────────────────────────────

    private fun onLinkEstablished(link: Link) {
        Log.i(TAG, "New link established from remote")
        val session = HubSession(link)
        sessions[link] = session
        _connectedClients.value = sessions.size

        link.setLinkClosedCallback { closedLink ->
            onLinkClosed(closedLink)
        }
        link.setPacketCallback { data, _ ->
            onPacket(link, data)
        }
    }

    private fun onLinkClosed(link: Link) {
        val session = sessions.remove(link) ?: return
        val peerHash = session.peerHash

        // Notify all rooms this session was in
        for (room in session.rooms) {
            val remaining = roomManager.getMembers(room).filter { it != link }
            roomManager.removeMember(room, link)

            if (peerHash != null && remaining.isNotEmpty()) {
                val parted = RrcEnvelope.make(
                    T_PARTED, src = identity.hash, room = room,
                    body = listOf(peerHash),
                )
                val payload = RrcCodec.encode(parted)
                for (memberLink in remaining) {
                    try { memberLink.send(payload) } catch (_: Exception) {}
                }
            }
        }

        _connectedClients.value = sessions.size
        Log.d(TAG, "Link closed, clients: ${sessions.size}")
    }

    // ── Packet handling ────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun onPacket(link: Link, data: ByteArray) {
        val session = sessions[link] ?: return

        if (session.peerHash == null) {
            val ri = link.getRemoteIdentity()
            if (ri != null) session.peerHash = ri.hash
        }

        val env: Map<Int, Any?>
        try {
            env = RrcCodec.decode(data)
            RrcEnvelope.validate(env)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode/validate packet: ${e.message}")
            sendError(link, "bad message: ${e.message}")
            return
        }

        val type = env[K_T] as? Int ?: return

        if (!session.welcomed) {
            if (type != T_HELLO) {
                sendError(link, "send HELLO first")
                return
            }
            val nick = env[K_NICK] as? String
            if (nick != null) session.nick = normalizeNick(nick)
            session.welcomed = true
            sendWelcome(link)
            greeting?.let { sendNotice(link, null, it) }
            return
        }

        when (type) {
            T_HELLO -> handleReHello(link, session, env)
            T_JOIN -> handleJoin(link, session, env)
            T_PART -> handlePart(link, session, env)
            T_MSG, T_NOTICE -> handleMessage(link, session, env)
            T_PING -> {
                val body = env[K_BODY]
                val pong = RrcEnvelope.make(T_PONG, src = identity.hash, body = body)
                sendPacket(link, pong)
            }
            T_PONG -> { /* ignored */ }
        }
    }

    // ── T_HELLO (re-auth) ──────────────────────────────────────────────

    private fun handleReHello(link: Link, session: HubSession, env: Map<Int, Any?>) {
        val oldRooms = session.rooms.toSet()
        session.rooms.clear()
        session.welcomed = false
        for (r in oldRooms) {
            roomManager.removeMember(r, link)
        }
        val nick = env[K_NICK] as? String
        if (nick != null) session.nick = normalizeNick(nick)
        session.welcomed = true
        sendWelcome(link)
        greeting?.let { sendNotice(link, null, it) }
    }

    // ── T_JOIN ─────────────────────────────────────────────────────────

    private fun handleJoin(link: Link, session: HubSession, env: Map<Int, Any?>) {
        val room = (env[K_ROOM] as? String)?.trim()?.lowercase()
        if (room.isNullOrEmpty()) {
            sendError(link, "JOIN requires room name")
            return
        }
        if (session.rooms.size >= RrcConstants.DEFAULT_MAX_ROOMS_PER_SESSION) {
            sendError(link, "too many rooms")
            return
        }

        val peerHash = session.peerHash ?: return
        val bodyKey = env[K_BODY] as? String

        // Join validation (bans, invite-only, key)
        val rejectReason = roomManager.checkJoinAllowed(room, peerHash, bodyKey)
        if (rejectReason != null) {
            sendError(link, rejectReason, room)
            return
        }

        // First joiner becomes founder
        val isFirstJoiner = roomManager.getMembers(room).isEmpty()
        if (isFirstJoiner) {
            roomManager.getOrCreateState(room, founder = peerHash)
        } else {
            roomManager.getOrCreateState(room)
        }

        // Notify existing members: T_JOINED body=[joinerHash]
        val existingMembers = roomManager.getMembers(room).toList()
        if (existingMembers.isNotEmpty()) {
            val notification = RrcEnvelope.make(
                T_JOINED, src = identity.hash, room = room,
                body = listOf(peerHash),
            )
            val notificationPayload = RrcCodec.encode(notification)
            for (memberLink in existingMembers) {
                try { memberLink.send(notificationPayload) } catch (_: Exception) {}
            }
        }

        // Add to room
        session.rooms.add(room)
        roomManager.addMember(room, link)

        // Send T_JOINED to joiner: body=[all member hashes]
        val allMemberHashes = mutableListOf<ByteArray>()
        for (memberLink in roomManager.getMembers(room)) {
            val s = sessions[memberLink]
            val ph = s?.peerHash
            if (ph != null) allMemberHashes.add(ph)
        }
        val joinedEnv = RrcEnvelope.make(
            T_JOINED, src = identity.hash, room = room,
            body = allMemberHashes,
        )
        sendPacket(link, joinedEnv)

        // Remove from invited list (consume invite)
        val st = roomManager.getState(room)
        st?.invited?.remove(peerHash.asKey())

        // Send join notice with room info
        val registered = st?.registered == true
        val topic = st?.topic
        val modeStr = roomManager.getModeString(room)
        val regTxt = if (registered) "registered" else "unregistered"
        val topicTxt = topic ?: "(none)"
        sendNotice(link, room, "room $room: $regTxt; mode=$modeStr; topic=$topicTxt")
    }

    // ── T_PART ─────────────────────────────────────────────────────────

    private fun handlePart(link: Link, session: HubSession, env: Map<Int, Any?>) {
        val room = (env[K_ROOM] as? String)?.trim()?.lowercase()
        if (room.isNullOrEmpty()) {
            sendError(link, "PART requires room name")
            return
        }

        val peerHash = session.peerHash
        session.rooms.remove(room)

        // Notify remaining members with parter's hash
        val remaining = roomManager.getMembers(room).filter { it != link }
        roomManager.removeMember(room, link)

        if (peerHash != null && remaining.isNotEmpty()) {
            val notification = RrcEnvelope.make(
                T_PARTED, src = identity.hash, room = room,
                body = listOf(peerHash),
            )
            val notificationPayload = RrcCodec.encode(notification)
            for (memberLink in remaining) {
                try { memberLink.send(notificationPayload) } catch (_: Exception) {}
            }
        }

        // Send PARTED to the departing client with own hash
        val partedBody = if (peerHash != null) listOf(peerHash) else null
        val parted = RrcEnvelope.make(T_PARTED, src = identity.hash, room = room, body = partedBody)
        sendPacket(link, parted)
    }

    // ── T_MSG / T_NOTICE ───────────────────────────────────────────────

    private fun handleMessage(link: Link, session: HubSession, env: Map<Int, Any?>) {
        val body = env[K_BODY]
        val room = (env[K_ROOM] as? String)?.trim()?.lowercase()
        val peerHash = session.peerHash

        // Slash command dispatch
        if (body is String && body.trim().startsWith("/")) {
            val handled = commandHandler.handle(session, room, body)
            if (handled) return
            sendError(link, "unrecognized command", room)
            return
        }

        // Room required for regular messages
        if (room.isNullOrEmpty()) {
            sendError(link, "message requires room name")
            return
        }

        // +n check: no outside messages
        if (room !in session.rooms) {
            val st = roomManager.getState(room)
            if (st == null) {
                sendError(link, "no such room", room)
                return
            }
            if (st.noOutsideMsgs) {
                sendError(link, "no outside messages (+n)", room)
                return
            }
        }

        // Ban check
        if (roomManager.isBanned(room, peerHash)) {
            sendError(link, "banned from room", room)
            return
        }

        // +m check: moderation
        val st = roomManager.getState(room)
        if (st != null && st.moderated && !roomManager.isVoiced(room, peerHash)) {
            sendError(link, "room is moderated (+m)", room)
            return
        }

        // Stamp src/nick and forward
        val forwarded = env.toMutableMap()
        peerHash?.let { forwarded[K_SRC] = it }
        session.nick?.let { forwarded[K_NICK] = it }

        // Accept nick updates from the message
        val incomingNick = env[K_NICK] as? String
        if (incomingNick != null) {
            val normalized = normalizeNick(incomingNick)
            if (normalized != null) {
                val oldNick = session.nick
                session.nick = normalized
                forwarded[K_NICK] = normalized
                updateNickIndex(link, oldNick, normalized)
            }
        }
        forwarded[K_ROOM] = room

        val payload = RrcCodec.encode(forwarded)
        for (memberLink in roomManager.getMembers(room)) {
            try { memberLink.send(payload) } catch (_: Exception) {}
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun sendWelcome(link: Link) {
        val welcomeBody = mapOf<Int, Any?>(
            B_WELCOME_HUB to hubName,
            B_WELCOME_VER to 1,
            B_WELCOME_CAPS to emptyMap<Int, Any?>(),
            B_WELCOME_LIMITS to mapOf<Int, Any?>(
                L_MAX_NICK_BYTES to RrcConstants.DEFAULT_MAX_NICK_BYTES,
                L_MAX_ROOM_NAME_BYTES to RrcConstants.DEFAULT_MAX_ROOM_NAME_BYTES,
                L_MAX_MSG_BODY_BYTES to RrcConstants.DEFAULT_MAX_MSG_BODY_BYTES,
                L_MAX_ROOMS_PER_SESSION to RrcConstants.DEFAULT_MAX_ROOMS_PER_SESSION,
                L_RATE_LIMIT_MSGS_PER_MINUTE to RrcConstants.DEFAULT_RATE_LIMIT_MSGS_PER_MINUTE,
            ),
        )
        val env = RrcEnvelope.make(T_WELCOME, src = identity.hash, body = welcomeBody)
        sendPacket(link, env)
    }

    private fun sendPacket(link: Link, env: Map<Int, Any?>) {
        try {
            val payload = RrcCodec.encode(env)
            link.send(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send packet", e)
        }
    }

    private fun normalizeNick(nick: String): String? {
        val trimmed = nick.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.toByteArray().size > RrcConstants.DEFAULT_MAX_NICK_BYTES) return null
        return trimmed
    }
}
