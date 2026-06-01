// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rrc

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tech.torlando.eridanus.rns.RnsBackend
import tech.torlando.eridanus.rns.RnsDestination
import tech.torlando.eridanus.rns.RnsDestinationDirection
import tech.torlando.eridanus.rns.RnsDestinationType
import tech.torlando.eridanus.rns.RnsIdentity
import tech.torlando.eridanus.rns.RnsLink
import java.security.MessageDigest
import java.security.SecureRandom
import tech.torlando.eridanus.rrc.RrcConstants.B_WELCOME_CAPS
import tech.torlando.eridanus.rrc.RrcConstants.B_WELCOME_HUB
import tech.torlando.eridanus.rrc.RrcConstants.CAP_ACTION
import tech.torlando.eridanus.rrc.RrcConstants.B_WELCOME_LIMITS
import tech.torlando.eridanus.rrc.RrcConstants.B_WELCOME_VER
import tech.torlando.eridanus.rrc.RrcConstants.DEST_NAME
import tech.torlando.eridanus.rrc.RrcConstants.K_BODY
import tech.torlando.eridanus.rrc.RrcConstants.K_NICK
import tech.torlando.eridanus.rrc.RrcConstants.K_ROOM
import tech.torlando.eridanus.rrc.RrcConstants.K_SRC
import tech.torlando.eridanus.rrc.RrcConstants.K_T
import tech.torlando.eridanus.rrc.RrcConstants.L_MAX_MSG_BODY_BYTES
import tech.torlando.eridanus.rrc.RrcConstants.L_MAX_NICK_BYTES
import tech.torlando.eridanus.rrc.RrcConstants.L_MAX_ROOMS_PER_SESSION
import tech.torlando.eridanus.rrc.RrcConstants.L_MAX_ROOM_NAME_BYTES
import tech.torlando.eridanus.rrc.RrcConstants.L_RATE_LIMIT_MSGS_PER_MINUTE
import tech.torlando.eridanus.rrc.RrcConstants.T_ACTION
import tech.torlando.eridanus.rrc.RrcConstants.T_ERROR
import tech.torlando.eridanus.rrc.RrcConstants.T_HELLO
import tech.torlando.eridanus.rrc.RrcConstants.T_JOIN
import tech.torlando.eridanus.rrc.RrcConstants.T_JOINED
import tech.torlando.eridanus.rrc.RrcConstants.T_MSG
import tech.torlando.eridanus.rrc.RrcConstants.T_NOTICE
import tech.torlando.eridanus.rrc.RrcConstants.T_PART
import tech.torlando.eridanus.rrc.RrcConstants.T_PARTED
import tech.torlando.eridanus.rrc.RrcConstants.T_PING
import tech.torlando.eridanus.rrc.RrcConstants.T_PONG
import tech.torlando.eridanus.rrc.RrcConstants.T_RESOURCE_ENVELOPE
import tech.torlando.eridanus.rrc.RrcConstants.T_WELCOME

private class RateState(
    var tokens: Double,
    var lastRefill: Long = System.nanoTime(),
)

data class HubSession(
    val link: RnsLink,
    var welcomed: Boolean = false,
    var nick: String? = null,
    var peerHash: ByteArray? = null,
    val rooms: MutableSet<String> = mutableSetOf(),
    var awaitingPong: Long? = null,
)


class RrcHub(
    internal val identity: RnsIdentity,
    private val backend: RnsBackend,
    var hubName: String = "Eridanus Hub",
) {
    companion object {
        private const val TAG = "RrcHub"
        private const val MAX_NOTICE_CHUNK_CHARS = 512
    }

    // Hub configuration
    var greeting: String? = null
    var defaultRooms: List<tech.torlando.eridanus.data.DefaultRoomConfig> = emptyList()

    private var destination: RnsDestination? = null
    private val sessions = mutableMapOf<RnsLink, HubSession>()
    private val rateLimits = mutableMapOf<RnsLink, RateState>()
    internal val roomManager = HubRoomManager()
    private val commandHandler = HubCommandHandler(this, roomManager)
    private var defaultRoomInitialized = false

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients: StateFlow<Int> = _connectedClients

    val destHash: ByteArray? get() = destination?.hash

    var pingIntervalSeconds: Int = 60
    var pingTimeoutSeconds: Int = 30

    private var running = false
    private var announceJob: Job? = null
    private var pingJob: Job? = null

    fun start() {
        if (running) return

        val parsed = backend.destinations.appAndAspectsFromName(DEST_NAME) ?: return
        val (appName, aspects) = parsed

        val dest = backend.destinations.create(
            identity,
            RnsDestinationDirection.IN,
            RnsDestinationType.SINGLE,
            appName,
            *aspects.toTypedArray(),
        )

        backend.transport.registerDestination(dest)

        dest.setLinkEstablishedCallback { link ->
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

    fun startPingLoop(scope: CoroutineScope) {
        stopPingLoop()
        if (pingIntervalSeconds <= 0) return
        pingJob = scope.launch {
            while (true) {
                delay(pingIntervalSeconds * 1000L)
                if (!running) break
                pingIdleClients()
            }
        }
        Log.i(TAG, "Ping loop started: interval=${pingIntervalSeconds}s timeout=${pingTimeoutSeconds}s")
    }

    fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun pingIdleClients() {
        val now = System.nanoTime()
        val timeoutNanos = pingTimeoutSeconds * 1_000_000_000L
        val toTeardown = mutableListOf<RnsLink>()
        val toPing = mutableListOf<RnsLink>()

        synchronized(sessions) {
            for ((link, session) in sessions) {
                if (!session.welcomed) continue

                val awaiting = session.awaitingPong
                if (awaiting != null && pingTimeoutSeconds > 0 && (now - awaiting) > timeoutNanos) {
                    toTeardown.add(link)
                    continue
                }

                if (awaiting == null) {
                    session.awaitingPong = now
                    toPing.add(link)
                }
            }
        }

        for (link in toTeardown) {
            Log.i(TAG, "Ping timeout, tearing down link")
            try { link.teardown() } catch (_: Exception) {}
        }

        for (link in toPing) {
            val ping = RrcEnvelope.make(T_PING, src = identity.hash, body = now)
            try {
                val payload = RrcCodec.encode(ping)
                link.send(payload)
            } catch (_: Exception) {}
        }
    }

    private fun refillAndTake(link: RnsLink): Boolean {
        val state = rateLimits[link] ?: return true
        val now = System.nanoTime()
        val perMin = RrcConstants.DEFAULT_RATE_LIMIT_MSGS_PER_MINUTE.toDouble().coerceAtLeast(1.0)
        val ratePerSec = perMin / 60.0
        val elapsedSec = (now - state.lastRefill).coerceAtLeast(0) / 1_000_000_000.0
        state.tokens = (state.tokens + elapsedSec * ratePerSec).coerceAtMost(perMin)
        state.lastRefill = now

        if (state.tokens < 1.0) return false
        state.tokens -= 1.0
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        stopAnnounceLoop()
        stopPingLoop()

        for ((link, _) in sessions) {
            try { link.teardown() } catch (_: Exception) {}
        }
        sessions.clear()
        rateLimits.clear()
        roomManager.clear()
        defaultRoomInitialized = false
        _connectedClients.value = 0
        destination?.let { backend.transport.deregisterDestination(it) }
        destination = null
        Log.i(TAG, "Hub stopped")
    }

    private fun initDefaultRoom() {
        if (defaultRoomInitialized) return
        defaultRoomInitialized = true
        Log.i(TAG, "initDefaultRoom: ${defaultRooms.size} default rooms configured")
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

    internal fun getSession(link: RnsLink): HubSession? = sessions[link]

    internal fun allSessions(): List<Pair<RnsLink, HubSession>> =
        sessions.entries.map { it.key to it.value }

    internal fun sendNotice(link: RnsLink, room: String?, text: String) {
        val env = RrcEnvelope.make(T_NOTICE, src = identity.hash, room = room, body = text)
        val payload = RrcCodec.encode(env)
        val mdu = link.getMdu()

        if (mdu == null || payload.size <= mdu) {
            try { link.send(payload) } catch (e: Exception) {
                Log.w(TAG, "Failed to send notice", e)
            }
            return
        }

        // Too large for a single packet — try resource transfer
        val textBytes = text.toByteArray(Charsets.UTF_8)
        if (sendViaResource(link, "notice", textBytes, room)) return

        // Fall back to chunking
        sendNoticeChunked(link, room, text, mdu)
    }

    private fun sendNoticeChunked(link: RnsLink, room: String?, text: String, mdu: Int) {
        var remaining = text
        var chunkSize = MAX_NOTICE_CHUNK_CHARS
        while (remaining.isNotEmpty()) {
            val take = remaining.length.coerceAtMost(chunkSize)
            val chunk = remaining.substring(0, take)
            val env = RrcEnvelope.make(T_NOTICE, src = identity.hash, room = room, body = chunk)
            try {
                val payload = RrcCodec.encode(env)
                if (payload.size <= mdu) {
                    link.send(payload)
                    remaining = remaining.substring(take)
                    chunkSize = chunkSize.coerceAtMost(MAX_NOTICE_CHUNK_CHARS)
                } else if (chunkSize <= 1) {
                    Log.w(TAG, "Notice chunk won't fit MTU, dropping ${remaining.length} chars")
                    break
                } else {
                    chunkSize = (chunkSize / 2).coerceAtLeast(1)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send notice chunk", e)
                break
            }
        }
    }

    private fun sendViaResource(link: RnsLink, kind: String, payload: ByteArray, room: String?): Boolean {
        val rid = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(payload)

        // Send resource envelope first
        val resBody = mapOf<Int, Any?>(
            RrcConstants.B_RES_ID to rid,
            RrcConstants.B_RES_KIND to kind,
            RrcConstants.B_RES_SIZE to payload.size,
            RrcConstants.B_RES_SHA256 to sha256,
            RrcConstants.B_RES_ENCODING to "utf-8",
        )
        val env = RrcEnvelope.make(T_RESOURCE_ENVELOPE, src = identity.hash, room = room, body = resBody)
        try {
            val envPayload = RrcCodec.encode(env)
            link.send(envPayload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send resource envelope", e)
            return false
        }

        // Create and advertise the resource
        return try {
            backend.resources.create(
                data = payload,
                link = link,
                advertise = true,
                autoCompress = false,
                requestId = rid,
            )
            Log.d(TAG, "Sent ${payload.size} bytes via resource transfer (kind=$kind)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Resource transfer failed", e)
            false
        }
    }

    internal fun sendError(link: RnsLink, text: String, room: String? = null) {
        val env = RrcEnvelope.make(T_ERROR, src = identity.hash, room = room, body = text)
        sendPacket(link, env)
    }

    internal fun updateNickIndex(@Suppress("UNUSED_PARAMETER") link: RnsLink, @Suppress("UNUSED_PARAMETER") oldNick: String?, @Suppress("UNUSED_PARAMETER") newNick: String) {
        // Nick tracked on session; command handler iterates sessions for lookup
    }

    // ── Link lifecycle ─────────────────────────────────────────────────

    /**
     * Accept an in-process [LoopbackLink] from a client running in this same
     * process (own-hub connect). Routes through the normal link-established
     * path so the session, callbacks and roster behave exactly like a network
     * client — the only difference is the link never touches Reticulum.
     */
    internal fun acceptLoopbackClient(link: RnsLink) {
        onLinkEstablished(link)
    }

    private fun onLinkEstablished(link: RnsLink) {
        Log.i(TAG, "New link established from remote")
        val session = HubSession(link)
        sessions[link] = session
        rateLimits[link] = RateState(tokens = RrcConstants.DEFAULT_RATE_LIMIT_MSGS_PER_MINUTE.toDouble())
        _connectedClients.value = sessions.size

        link.setLinkClosedCallback { closedLink ->
            onLinkClosed(closedLink)
        }
        link.setPacketCallback { data ->
            onPacket(link, data)
        }
    }

    private fun onLinkClosed(link: RnsLink) {
        val session = sessions.remove(link) ?: return
        rateLimits.remove(link)
        val peerHash = session.peerHash

        // Notify all rooms this session was in
        for (room in session.rooms) {
            val remaining = roomManager.getMembers(room).filter { it != link }
            roomManager.removeMember(room, link)

            if (peerHash != null && remaining.isNotEmpty()) {
                // Advisory K_NICK (matches rrcd 0.3.2) so a "<nick> left"
                // renders for an abrupt link drop too, not just a clean PART.
                val parted = RrcEnvelope.make(
                    T_PARTED, src = identity.hash, room = room,
                    body = listOf(peerHash),
                    nick = session.nick,
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
    private fun onPacket(link: RnsLink, data: ByteArray) {
        val session = sessions[link] ?: return

        if (session.peerHash == null) {
            val ri = link.getRemoteIdentity()
            if (ri != null) session.peerHash = ri.hash
        }

        // Global ban check
        if (roomManager.isGloballyBanned(session.peerHash)) {
            sendError(link, "banned from hub")
            try { link.teardown() } catch (_: Exception) {}
            return
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

        // PONG bypasses rate limiting
        if (type != T_PONG && !refillAndTake(link)) {
            Log.d(TAG, "Rate limited peer=${session.peerHash?.let { it.joinToString("") { b -> "%02x".format(b) }.take(12) }}")
            sendError(link, "rate limited")
            return
        }

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
            T_ACTION -> handleMessage(link, session, env, isAction = true)
            T_PING -> {
                val body = env[K_BODY]
                val pong = RrcEnvelope.make(T_PONG, src = identity.hash, body = body)
                sendPacket(link, pong)
            }
            T_PONG -> { session.awaitingPong = null }
        }
    }

    // ── T_HELLO (re-auth) ──────────────────────────────────────────────

    private fun handleReHello(link: RnsLink, session: HubSession, env: Map<Int, Any?>) {
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

    private fun handleJoin(link: RnsLink, session: HubSession, env: Map<Int, Any?>) {
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

        // Notify existing members: T_JOINED body=[joinerHash]. Attach the
        // joiner's nick as advisory K_NICK (matches rrcd 0.3.2) so clients
        // can render "<nick> joined" immediately without a follow-up /who.
        val existingMembers = roomManager.getMembers(room).toList()
        if (existingMembers.isNotEmpty()) {
            val notification = RrcEnvelope.make(
                T_JOINED, src = identity.hash, room = room,
                body = listOf(peerHash),
                nick = session.nick,
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

    private fun handlePart(link: RnsLink, session: HubSession, env: Map<Int, Any?>) {
        val room = (env[K_ROOM] as? String)?.trim()?.lowercase()
        if (room.isNullOrEmpty()) {
            sendError(link, "PART requires room name")
            return
        }

        val peerHash = session.peerHash
        session.rooms.remove(room)

        // Notify remaining members with parter's hash + advisory K_NICK
        // (matches rrcd 0.3.2) so clients can render "<nick> left" even if
        // they never had the parter in their member list.
        val remaining = roomManager.getMembers(room).filter { it != link }
        roomManager.removeMember(room, link)

        if (peerHash != null && remaining.isNotEmpty()) {
            val notification = RrcEnvelope.make(
                T_PARTED, src = identity.hash, room = room,
                body = listOf(peerHash),
                nick = session.nick,
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

    private fun handleMessage(link: RnsLink, session: HubSession, env: Map<Int, Any?>, isAction: Boolean = false) {
        val body = env[K_BODY]
        val room = (env[K_ROOM] as? String)?.trim()?.lowercase()
        val peerHash = session.peerHash

        // Slash command dispatch. ACTION bodies are room content and are
        // never interpreted as commands (matches rrcd 0.3.0+), so a "/me"-
        // style action whose text starts with "/" is forwarded verbatim.
        if (!isAction && body is String && body.trim().startsWith("/")) {
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

    private fun sendWelcome(link: RnsLink) {
        val welcomeBody = mapOf<Int, Any?>(
            B_WELCOME_HUB to hubName,
            B_WELCOME_VER to 1,
            B_WELCOME_CAPS to mapOf<Int, Any?>(CAP_ACTION to true),
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

    private fun sendPacket(link: RnsLink, env: Map<Int, Any?>) {
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
