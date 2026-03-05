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
    private val identity: Identity,
    var hubName: String = "Ara Hub",
) {
    companion object {
        private const val TAG = "RrcHub"
    }

    private var destination: Destination? = null
    private val sessions = mutableMapOf<Link, HubSession>()
    private val roomMembers = mutableMapOf<String, MutableSet<Link>>()

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
        roomMembers.clear()
        _connectedClients.value = 0
        destination = null
        Log.i(TAG, "Hub stopped")
    }

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
        for (room in session.rooms) {
            roomMembers[room]?.remove(link)
            if (roomMembers[room]?.isEmpty() == true) {
                roomMembers.remove(room)
            }
        }
        _connectedClients.value = sessions.size
        Log.d(TAG, "Link closed, clients: ${sessions.size}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun onPacket(link: Link, data: ByteArray) {
        Log.i(TAG, "Packet received (${data.size} bytes)")
        val session = sessions[link] ?: run {
            Log.w(TAG, "No session for link")
            return
        }

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
        Log.i(TAG, "Received message type=$type welcomed=${session.welcomed}")

        if (!session.welcomed) {
            if (type != T_HELLO) {
                sendError(link, "send HELLO first")
                return
            }
            val nick = env[K_NICK] as? String
            if (nick != null) session.nick = normalizeNick(nick)
            session.welcomed = true
            sendWelcome(link)
            Log.i(TAG, "HELLO from ${session.nick ?: "anonymous"}")
            return
        }

        when (type) {
            T_HELLO -> {
                // Re-hello: reset session
                val oldRooms = session.rooms.toSet()
                session.rooms.clear()
                session.welcomed = false
                for (r in oldRooms) {
                    roomMembers[r]?.remove(link)
                }
                val nick = env[K_NICK] as? String
                if (nick != null) session.nick = normalizeNick(nick)
                session.welcomed = true
                sendWelcome(link)
            }

            T_JOIN -> {
                val room = (env[K_ROOM] as? String)?.trim()?.lowercase()
                if (room.isNullOrEmpty()) {
                    sendError(link, "JOIN requires room name")
                    return
                }
                if (session.rooms.size >= RrcConstants.DEFAULT_MAX_ROOMS_PER_SESSION) {
                    sendError(link, "too many rooms")
                    return
                }
                session.rooms.add(room)
                val members = roomMembers.getOrPut(room) { mutableSetOf() }
                members.add(link)

                // Notify all members (including joiner)
                for (memberLink in members) {
                    val joined = RrcEnvelope.make(T_JOINED, src = identity.hash, room = room)
                    sendPacket(memberLink, joined)
                }
            }

            T_PART -> {
                val room = (env[K_ROOM] as? String)?.trim()?.lowercase()
                if (room.isNullOrEmpty()) {
                    sendError(link, "PART requires room name")
                    return
                }
                session.rooms.remove(room)
                roomMembers[room]?.remove(link)

                // Notify remaining members
                roomMembers[room]?.forEach { memberLink ->
                    val parted = RrcEnvelope.make(T_PARTED, src = identity.hash, room = room)
                    sendPacket(memberLink, parted)
                }

                // Send PARTED to the departing client
                val parted = RrcEnvelope.make(T_PARTED, src = identity.hash, room = room)
                sendPacket(link, parted)

                if (roomMembers[room]?.isEmpty() == true) roomMembers.remove(room)
            }

            T_MSG, T_NOTICE -> {
                val room = (env[K_ROOM] as? String)?.trim()?.lowercase()
                if (room.isNullOrEmpty()) {
                    sendError(link, "message requires room name")
                    return
                }
                if (room !in session.rooms) {
                    sendError(link, "not in room")
                    return
                }

                // Stamp the src and nick on the envelope
                val forwarded = env.toMutableMap()
                session.peerHash?.let { forwarded[K_SRC] = it }
                session.nick?.let { forwarded[K_NICK] = it }
                // Also accept nick updates from the message
                val incomingNick = env[K_NICK] as? String
                if (incomingNick != null) {
                    val normalized = normalizeNick(incomingNick)
                    if (normalized != null) {
                        session.nick = normalized
                        forwarded[K_NICK] = normalized
                    }
                }
                forwarded[K_ROOM] = room

                val payload = RrcCodec.encode(forwarded)
                roomMembers[room]?.forEach { memberLink ->
                    try {
                        memberLink.send(payload)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to forward message", e)
                    }
                }
            }

            T_PING -> {
                val body = env[K_BODY]
                val pong = RrcEnvelope.make(T_PONG, src = identity.hash, body = body)
                sendPacket(link, pong)
            }

            T_PONG -> { /* ignored */ }
        }
    }

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

    private fun sendError(link: Link, text: String, room: String? = null) {
        val env = RrcEnvelope.make(T_ERROR, src = identity.hash, room = room, body = text)
        sendPacket(link, env)
    }

    private fun sendPacket(link: Link, env: Map<Int, Any?>) {
        try {
            val payload = RrcCodec.encode(env)
            val sent = link.send(payload)
            Log.i(TAG, "sendPacket type=${env[K_T]} size=${payload.size} sent=$sent")
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
