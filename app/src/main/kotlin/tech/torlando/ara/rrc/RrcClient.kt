package tech.torlando.ara.rrc

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.transport.Transport
import tech.torlando.ara.rrc.RrcConstants.B_HELLO_CAPS
import tech.torlando.ara.rrc.RrcConstants.B_HELLO_NAME
import tech.torlando.ara.rrc.RrcConstants.B_HELLO_VER
import tech.torlando.ara.rrc.RrcConstants.B_WELCOME_HUB
import tech.torlando.ara.rrc.RrcConstants.B_WELCOME_LIMITS
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

sealed class RrcEvent {
    data class Welcome(val hubName: String?, val env: Map<Int, Any?>) : RrcEvent()
    data class MessageReceived(val room: String, val nick: String?, val body: String, val src: ByteArray?) : RrcEvent()
    data class NoticeReceived(val room: String?, val body: String) : RrcEvent()
    data class ErrorReceived(val room: String?, val text: String) : RrcEvent()
    data class Joined(val room: String, val members: List<ByteArray>?) : RrcEvent()
    data class Parted(val room: String) : RrcEvent()
    data object Disconnected : RrcEvent()
    data class ConnectionFailed(val reason: String) : RrcEvent()
}

enum class ClientState {
    DISCONNECTED,
    CONNECTING,
    AWAITING_WELCOME,
    ACTIVE,
}

class RrcClient(
    private val identity: Identity,
    var nickname: String? = null,
) {
    companion object {
        private const val TAG = "RrcClient"
        private const val APP_NAME = "Ara"
        private const val APP_VERSION = "1.0"
    }

    private var link: Link? = null
    private val _joinedRooms = mutableSetOf<String>()
    val joinedRooms: Set<String> get() = _joinedRooms.toSet()

    var state: ClientState = ClientState.DISCONNECTED
        private set

    var maxNickBytes = RrcConstants.DEFAULT_MAX_NICK_BYTES
        private set
    var maxRoomNameBytes = RrcConstants.DEFAULT_MAX_ROOM_NAME_BYTES
        private set
    var maxMsgBodyBytes = RrcConstants.DEFAULT_MAX_MSG_BODY_BYTES
        private set
    var maxRoomsPerSession = RrcConstants.DEFAULT_MAX_ROOMS_PER_SESSION
        private set

    private val _events = MutableSharedFlow<RrcEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RrcEvent> = _events

    fun connect(hubDestHash: ByteArray, knownIdentity: Identity? = null) {
        val hexHash = hubDestHash.joinToString("") { "%02x".format(it) }
        Log.i(TAG, "connect: starting connection to $hexHash (knownIdentity=${knownIdentity != null})")
        if (state != ClientState.DISCONNECTED) {
            disconnect()
        }
        state = ClientState.CONNECTING

        try {
            val isLocal = Transport.findDestination(hubDestHash) != null
            if (!isLocal) {
                Transport.requestPath(hubDestHash)
                Log.d(TAG, "connect: requested path, waiting...")

                // Wait for path
                var attempts = 0
                while (!Transport.hasPath(hubDestHash) && attempts < 100) {
                    Thread.sleep(100)
                    attempts++
                }
                Log.i(TAG, "connect: path lookup done after $attempts attempts, hasPath=${Transport.hasPath(hubDestHash)}")
            } else {
                Log.i(TAG, "connect: destination is local, skipping path request")
            }

            val hubIdentity = knownIdentity ?: Identity.recall(hubDestHash)
            if (hubIdentity == null) {
                state = ClientState.DISCONNECTED
                _events.tryEmit(RrcEvent.ConnectionFailed("Could not recall hub identity"))
                return
            }

            val parsed = Destination.appAndAspectsFromName(DEST_NAME)
            if (parsed == null) {
                state = ClientState.DISCONNECTED
                _events.tryEmit(RrcEvent.ConnectionFailed("Invalid destination name: $DEST_NAME"))
                return
            }
            val (appName, aspects) = parsed

            val hubDest = Destination.create(
                hubIdentity,
                DestinationDirection.OUT,
                DestinationType.SINGLE,
                appName,
                *aspects.toTypedArray(),
            )
            Log.i(TAG, "connect: created OUT destination ${hubDest.hexHash}")

            val newLink = Link.create(hubDest,
                establishedCallback = { establishedLink ->
                    Log.d(TAG, "Link established")
                    try {
                        establishedLink.identify(identity)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to identify on link", e)
                        state = ClientState.DISCONNECTED
                        _events.tryEmit(RrcEvent.ConnectionFailed("Failed to identify: ${e.message}"))
                        return@create
                    }
                    state = ClientState.AWAITING_WELCOME
                    sendHello(establishedLink)
                },
                closedCallback = { _ ->
                    Log.d(TAG, "Link closed")
                    state = ClientState.DISCONNECTED
                    _joinedRooms.clear()
                    link = null
                    _events.tryEmit(RrcEvent.Disconnected)
                },
            )
            newLink.setPacketCallback { data, _ ->
                onPacket(data)
            }

            link = newLink
            Log.i(TAG, "connect: Link created, waiting for establishment")

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            state = ClientState.DISCONNECTED
            _events.tryEmit(RrcEvent.ConnectionFailed(e.message ?: "Unknown error"))
        }
    }

    fun disconnect() {
        val currentLink = link
        link = null
        _joinedRooms.clear()
        state = ClientState.DISCONNECTED
        currentLink?.teardown()
    }

    fun join(room: String) {
        val r = room.trim().lowercase()
        require(r.isNotEmpty()) { "Room name cannot be empty" }
        require(r.toByteArray().size <= maxRoomNameBytes) {
            "Room name too long: ${r.toByteArray().size} bytes > $maxRoomNameBytes"
        }
        require(_joinedRooms.size < maxRoomsPerSession) {
            "Already in $maxRoomsPerSession rooms"
        }
        send(RrcEnvelope.make(T_JOIN, src = identity.hash, room = r))
    }

    fun part(room: String) {
        val r = room.trim().lowercase()
        require(r.isNotEmpty()) { "Room name cannot be empty" }
        send(RrcEnvelope.make(T_PART, src = identity.hash, room = r))
        _joinedRooms.remove(r)
    }

    fun sendMessage(room: String, text: String) {
        val r = room.trim().lowercase()
        require(r.isNotEmpty()) { "Room name cannot be empty" }
        require(text.isNotBlank()) { "Message cannot be empty" }
        require(text.toByteArray().size <= maxMsgBodyBytes) {
            "Message too long: ${text.toByteArray().size} bytes > $maxMsgBodyBytes"
        }
        val env = RrcEnvelope.make(T_MSG, src = identity.hash, room = r, body = text, nick = nickname)
        send(env)
    }

    fun sendCommand(text: String) {
        require(text.isNotBlank()) { "Command cannot be empty" }
        val env = RrcEnvelope.make(T_MSG, src = identity.hash, body = text, nick = nickname)
        send(env)
    }

    fun ping() {
        send(RrcEnvelope.make(T_PING, src = identity.hash))
    }

    private fun sendHello(targetLink: Link) {
        val helloBody = mapOf<Int, Any?>(
            B_HELLO_NAME to APP_NAME,
            B_HELLO_VER to APP_VERSION,
            B_HELLO_CAPS to emptyMap<Int, Any?>(),
        )
        val env = RrcEnvelope.make(T_HELLO, src = identity.hash, body = helloBody, nick = nickname)
        val payload = RrcCodec.encode(env)
        targetLink.send(payload)
    }

    private fun send(env: Map<Int, Any?>) {
        val currentLink = link ?: throw IllegalStateException("Not connected")
        val payload = RrcCodec.encode(env)
        currentLink.send(payload)
    }

    @Suppress("UNCHECKED_CAST")
    private fun onPacket(data: ByteArray) {
        val env: Map<Int, Any?>
        try {
            env = RrcCodec.decode(data)
            RrcEnvelope.validate(env)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode packet: ${e.message}")
            return
        }

        val type = env[K_T] as? Int ?: return

        when (type) {
            T_WELCOME -> {
                state = ClientState.ACTIVE
                var hubName: String? = null
                val body = env[K_BODY]
                if (body is Map<*, *>) {
                    hubName = body[B_WELCOME_HUB] as? String
                    val limits = body[B_WELCOME_LIMITS]
                    if (limits is Map<*, *>) {
                        (limits[L_MAX_NICK_BYTES] as? Int)?.let { maxNickBytes = it }
                        (limits[L_MAX_ROOM_NAME_BYTES] as? Int)?.let { maxRoomNameBytes = it }
                        (limits[L_MAX_MSG_BODY_BYTES] as? Int)?.let { maxMsgBodyBytes = it }
                        (limits[L_MAX_ROOMS_PER_SESSION] as? Int)?.let { maxRoomsPerSession = it }
                    }
                }
                _events.tryEmit(RrcEvent.Welcome(hubName, env))
            }

            T_JOINED -> {
                val room = (env[K_ROOM] as? String)?.trim()?.lowercase() ?: return
                _joinedRooms.add(room)
                val body = env[K_BODY]
                val members = if (body is List<*>) {
                    body.filterIsInstance<ByteArray>()
                } else null
                _events.tryEmit(RrcEvent.Joined(room, members))
            }

            T_PARTED -> {
                val room = (env[K_ROOM] as? String)?.trim()?.lowercase() ?: return
                _joinedRooms.remove(room)
                _events.tryEmit(RrcEvent.Parted(room))
            }

            T_MSG -> {
                val room = (env[K_ROOM] as? String) ?: return
                val body = (env[K_BODY] as? String) ?: return
                val nick = env[K_NICK] as? String
                val src = env[K_SRC] as? ByteArray
                _events.tryEmit(RrcEvent.MessageReceived(room, nick, body, src))
            }

            T_NOTICE -> {
                val room = env[K_ROOM] as? String
                val body = (env[K_BODY] as? String) ?: return
                _events.tryEmit(RrcEvent.NoticeReceived(room, body))
            }

            T_ERROR -> {
                val room = env[K_ROOM] as? String
                val body = (env[K_BODY] as? String) ?: "Unknown error"
                _events.tryEmit(RrcEvent.ErrorReceived(room, body))
            }

            T_PING -> {
                val body = env[K_BODY]
                try {
                    send(RrcEnvelope.make(T_PONG, src = identity.hash, body = body))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send PONG", e)
                }
            }

            T_PONG -> {
                // Could track latency here
            }
        }
    }
}
