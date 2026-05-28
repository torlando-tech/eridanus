// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rrc

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import tech.torlando.eridanus.rns.RnsBackend
import tech.torlando.eridanus.rns.RnsDestinationDirection
import tech.torlando.eridanus.rns.RnsDestinationType
import tech.torlando.eridanus.rns.RnsIdentity
import tech.torlando.eridanus.rns.RnsLink
import tech.torlando.eridanus.rns.RnsResource
import tech.torlando.eridanus.rns.RnsResourceStrategy
import java.security.MessageDigest
import tech.torlando.eridanus.rrc.RrcConstants.B_HELLO_CAPS
import tech.torlando.eridanus.rrc.RrcConstants.B_HELLO_NAME
import tech.torlando.eridanus.rrc.RrcConstants.B_HELLO_VER
import tech.torlando.eridanus.rrc.RrcConstants.B_WELCOME_HUB
import tech.torlando.eridanus.rrc.RrcConstants.B_WELCOME_LIMITS
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
import tech.torlando.eridanus.rrc.RrcConstants.B_RES_ID
import tech.torlando.eridanus.rrc.RrcConstants.B_RES_KIND
import tech.torlando.eridanus.rrc.RrcConstants.B_RES_SIZE
import tech.torlando.eridanus.rrc.RrcConstants.B_RES_SHA256
import tech.torlando.eridanus.rrc.RrcConstants.B_RES_ENCODING
import tech.torlando.eridanus.rrc.RrcConstants.CAP_RESOURCE_ENVELOPE

sealed class RrcEvent {
    data class Welcome(val hubName: String?, val env: Map<Int, Any?>) : RrcEvent()
    data class MessageReceived(val room: String, val nick: String?, val body: String, val src: ByteArray?) : RrcEvent()
    data class NoticeReceived(val room: String?, val body: String) : RrcEvent()
    data class ErrorReceived(val room: String?, val text: String) : RrcEvent()
    data class Joined(val room: String, val members: List<ByteArray>?) : RrcEvent()
    data class Parted(val room: String) : RrcEvent()
    // nick is the advisory K_NICK the hub attaches to the JOINED/PARTED
    // fanout (rrcd 0.3.2+, and Eridanus's own hub). null when the hub
    // doesn't send it (older hubs) — the UI falls back to a short hash.
    data class MemberJoined(val room: String, val memberHash: ByteArray, val nick: String?) : RrcEvent()
    data class MemberParted(val room: String, val memberHash: ByteArray, val nick: String?) : RrcEvent()
    data object Disconnected : RrcEvent()
    data class ConnectionFailed(val reason: String) : RrcEvent()
}

enum class ClientState {
    DISCONNECTED,
    CONNECTING,
    AWAITING_WELCOME,
    ACTIVE,
}

private data class PendingResource(
    val resId: ByteArray,
    val kind: String,
    val size: Int,
    val sha256: ByteArray?,
    val encoding: String?,
)

class RrcClient(
    private val identity: RnsIdentity,
    private val backend: RnsBackend,
    var nickname: String? = null,
) {
    companion object {
        private const val TAG = "RrcClient"
        private const val APP_NAME = "Eridanus"
        private const val APP_VERSION = "1.0"
    }

    private var link: RnsLink? = null
    private val _joinedRooms = mutableSetOf<String>()
    private val _pendingResources = mutableMapOf<String, PendingResource>()
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

    fun connect(hubDestHash: ByteArray, knownIdentity: RnsIdentity? = null) {
        val hexHash = hubDestHash.joinToString("") { "%02x".format(it) }
        Log.i(TAG, "connect: starting connection to $hexHash (knownIdentity=${knownIdentity != null})")
        if (state != ClientState.DISCONNECTED) {
            disconnect()
        }
        state = ClientState.CONNECTING

        try {
            val isLocal = backend.transport.findDestination(hubDestHash) != null
            if (!isLocal) {
                backend.transport.requestPath(hubDestHash)
                Log.d(TAG, "connect: requested path, waiting...")

                // Wait for path (up to ~15 seconds)
                var attempts = 0
                while (!backend.transport.hasPath(hubDestHash) && attempts < 150) {
                    Thread.sleep(100)
                    attempts++
                }
                val hasPath = backend.transport.hasPath(hubDestHash)
                Log.i(TAG, "connect: path lookup done after $attempts attempts, hasPath=$hasPath")
                if (!hasPath) {
                    state = ClientState.DISCONNECTED
                    _events.tryEmit(RrcEvent.ConnectionFailed("No path found to hub"))
                    return
                }
            } else {
                Log.i(TAG, "connect: destination is local, skipping path request")
            }

            // Identity is learned from the path response (which is an announce).
            // Give the announce processing a moment to complete if needed.
            var hubIdentity = knownIdentity ?: backend.identities.recall(hubDestHash)
            if (hubIdentity == null) {
                Log.d(TAG, "connect: identity not yet available, waiting for announce processing...")
                var identityAttempts = 0
                while (hubIdentity == null && identityAttempts < 20) {
                    Thread.sleep(100)
                    hubIdentity = backend.identities.recall(hubDestHash)
                    identityAttempts++
                }
            }
            if (hubIdentity == null) {
                state = ClientState.DISCONNECTED
                _events.tryEmit(RrcEvent.ConnectionFailed("Could not recall hub identity"))
                return
            }

            val parsed = backend.destinations.appAndAspectsFromName(DEST_NAME)
            if (parsed == null) {
                state = ClientState.DISCONNECTED
                _events.tryEmit(RrcEvent.ConnectionFailed("Invalid destination name: $DEST_NAME"))
                return
            }
            val (appName, aspects) = parsed

            val hubDest = backend.destinations.create(
                hubIdentity,
                RnsDestinationDirection.OUT,
                RnsDestinationType.SINGLE,
                appName,
                *aspects.toTypedArray(),
            )
            Log.i(TAG, "connect: created OUT destination ${hubDest.hexHash}")

            val newLink = backend.links.create(hubDest,
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
                    setupResourceCallbacks(establishedLink)
                    state = ClientState.AWAITING_WELCOME
                    sendHello(establishedLink)
                },
                closedCallback = { _ ->
                    Log.d(TAG, "Link closed")
                    state = ClientState.DISCONNECTED
                    _joinedRooms.clear()
                    _pendingResources.clear()
                    link = null
                    _events.tryEmit(RrcEvent.Disconnected)
                },
            )
            newLink.setPacketCallback { data ->
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

    /**
     * Attach to a hub over an already-established [providedLink] — e.g. an
     * in-process [LoopbackLink] to a hub hosted by this same instance — and
     * run the HELLO handshake. Bypasses RNS link establishment entirely; used
     * for own-hub connect, where a real link would have to round-trip through
     * the shared-instance host and mis-route the loopback.
     */
    fun connectViaLink(providedLink: RnsLink) {
        if (state != ClientState.DISCONNECTED) disconnect()
        link = providedLink
        providedLink.setPacketCallback { data -> onPacket(data) }
        providedLink.setLinkClosedCallback { _ ->
            Log.d(TAG, "Link closed")
            state = ClientState.DISCONNECTED
            _joinedRooms.clear()
            _pendingResources.clear()
            link = null
            _events.tryEmit(RrcEvent.Disconnected)
        }
        setupResourceCallbacks(providedLink)
        state = ClientState.AWAITING_WELCOME
        Log.i(TAG, "connectViaLink: attached in-process, sending HELLO")
        sendHello(providedLink)
    }

    fun disconnect() {
        val currentLink = link
        link = null
        _joinedRooms.clear()
        state = ClientState.DISCONNECTED
        currentLink?.teardown()
    }

    fun join(room: String, key: String? = null) {
        val r = room.trim().lowercase()
        require(r.isNotEmpty()) { "Room name cannot be empty" }
        require(r.toByteArray().size <= maxRoomNameBytes) {
            "Room name too long: ${r.toByteArray().size} bytes > $maxRoomNameBytes"
        }
        require(_joinedRooms.size < maxRoomsPerSession) {
            "Already in $maxRoomsPerSession rooms"
        }
        send(RrcEnvelope.make(T_JOIN, src = identity.hash, room = r, body = key))
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

    fun sendCommand(text: String, room: String? = null) {
        require(text.isNotBlank()) { "Command cannot be empty" }
        Log.d(TAG, "sendCommand: text='$text' room=$room")
        val env = RrcEnvelope.make(T_MSG, src = identity.hash, room = room, body = text, nick = nickname)
        send(env)
    }

    fun ping() {
        send(RrcEnvelope.make(T_PING, src = identity.hash))
    }

    private fun sendHello(targetLink: RnsLink) {
        val caps = mapOf<Int, Any?>(
            CAP_RESOURCE_ENVELOPE to true,
        )
        val helloBody = mapOf<Int, Any?>(
            B_HELLO_NAME to APP_NAME,
            B_HELLO_VER to APP_VERSION,
            B_HELLO_CAPS to caps,
        )
        val env = RrcEnvelope.make(T_HELLO, src = identity.hash, body = helloBody, nick = nickname)
        val payload = RrcCodec.encode(env)
        targetLink.send(payload)
    }

    private fun setupResourceCallbacks(link: RnsLink) {
        link.setResourceStrategy(RnsResourceStrategy.ACCEPT_APP)
        link.setResourceCallback { advertisement ->
            // Accept if we have a pending expectation matching the request ID
            val reqId = advertisement.requestId
            if (reqId != null) {
                val hexId = reqId.joinToString("") { "%02x".format(it) }
                hexId in _pendingResources
            } else {
                // Accept unexpected resources too — server may send without envelope
                true
            }
        }
        link.setResourceConcludedCallback { resource ->
            onResourceConcluded(resource)
        }
    }

    private fun onResourceConcluded(resource: RnsResource) {
        val data = resource.data ?: return
        val reqId = resource.requestId
        val hexId = reqId?.joinToString("") { "%02x".format(it) }
        val pending = if (hexId != null) _pendingResources.remove(hexId) else null

        if (pending != null) {
            // Verify SHA256 if provided
            if (pending.sha256 != null) {
                val digest = MessageDigest.getInstance("SHA-256").digest(data)
                if (!digest.contentEquals(pending.sha256)) {
                    Log.w(TAG, "Resource SHA256 mismatch for $hexId")
                    return
                }
            }
            val text = data.toString(Charsets.UTF_8)
            when (pending.kind) {
                "notice", "motd" -> {
                    _events.tryEmit(RrcEvent.NoticeReceived(null, text))
                }
                else -> {
                    Log.d(TAG, "Received resource kind=${pending.kind}, size=${data.size}")
                    _events.tryEmit(RrcEvent.NoticeReceived(null, text))
                }
            }
        } else {
            // No matching envelope — treat as notice
            val text = data.toString(Charsets.UTF_8)
            _events.tryEmit(RrcEvent.NoticeReceived(null, text))
        }
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
        val bodyPreview = env[K_BODY]?.let { b -> "${b::class.simpleName}: ${if (b is String) "'$b'" else b}" }
        Log.d(TAG, "onPacket: type=$type room=${env[K_ROOM]} body=$bodyPreview")

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
                val body = env[K_BODY]
                val members = if (body is List<*>) {
                    body.filterIsInstance<ByteArray>()
                } else null
                if (room in _joinedRooms) {
                    // Already in this room — someone else joined. The hub
                    // (rrcd 0.3.2+ / Eridanus's own) attaches the joiner's
                    // advisory nick as K_NICK on this single-joiner fanout.
                    val joinerHash = members?.firstOrNull()
                    if (joinerHash != null) {
                        val nick = env[K_NICK] as? String
                        _events.tryEmit(RrcEvent.MemberJoined(room, joinerHash, nick))
                    }
                } else {
                    _joinedRooms.add(room)
                    _events.tryEmit(RrcEvent.Joined(room, members))
                }
            }

            T_PARTED -> {
                val room = (env[K_ROOM] as? String)?.trim()?.lowercase() ?: return
                val body = env[K_BODY]
                val partedHash = if (body is ByteArray) body
                    else if (body is List<*>) (body.firstOrNull() as? ByteArray)
                    else null
                val isSelf = partedHash != null && partedHash.contentEquals(identity.hash)
                if (isSelf || partedHash == null) {
                    _joinedRooms.remove(room)
                    _events.tryEmit(RrcEvent.Parted(room))
                } else {
                    // Advisory K_NICK on the PARTED fanout (rrcd 0.3.2+ /
                    // Eridanus's own hub) lets us render "<nick> left" even
                    // if we never had the parter in our member list.
                    val nick = env[K_NICK] as? String
                    _events.tryEmit(RrcEvent.MemberParted(room, partedHash, nick))
                }
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

            T_RESOURCE_ENVELOPE -> {
                val body = env[K_BODY]
                if (body is Map<*, *>) {
                    val resId = body[B_RES_ID] as? ByteArray ?: return
                    val kind = body[B_RES_KIND] as? String ?: "unknown"
                    val size = (body[B_RES_SIZE] as? Number)?.toInt() ?: 0
                    val sha256 = body[B_RES_SHA256] as? ByteArray
                    val encoding = body[B_RES_ENCODING] as? String
                    val hexId = resId.joinToString("") { "%02x".format(it) }
                    _pendingResources[hexId] = PendingResource(resId, kind, size, sha256, encoding)
                    Log.d(TAG, "Resource envelope: id=$hexId kind=$kind size=$size")
                }
            }
        }
    }
}
