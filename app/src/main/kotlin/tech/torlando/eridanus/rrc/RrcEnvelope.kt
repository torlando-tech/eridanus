package tech.torlando.eridanus.rrc

import tech.torlando.eridanus.rrc.RrcConstants.K_BODY
import tech.torlando.eridanus.rrc.RrcConstants.K_ID
import tech.torlando.eridanus.rrc.RrcConstants.K_NICK
import tech.torlando.eridanus.rrc.RrcConstants.K_ROOM
import tech.torlando.eridanus.rrc.RrcConstants.K_SRC
import tech.torlando.eridanus.rrc.RrcConstants.K_T
import tech.torlando.eridanus.rrc.RrcConstants.K_TS
import tech.torlando.eridanus.rrc.RrcConstants.K_V
import tech.torlando.eridanus.rrc.RrcConstants.RRC_VERSION
import java.security.SecureRandom

object RrcEnvelope {

    private val random = SecureRandom()

    fun make(
        type: Int,
        src: ByteArray,
        room: String? = null,
        body: Any? = null,
        nick: String? = null,
        mid: ByteArray? = null,
        ts: Long? = null,
    ): MutableMap<Int, Any?> {
        val env = mutableMapOf<Int, Any?>(
            K_V to RRC_VERSION,
            K_T to type,
            K_ID to (mid ?: generateMessageId()),
            K_TS to (ts ?: System.currentTimeMillis()),
            K_SRC to src,
        )
        if (room != null) env[K_ROOM] = room
        if (body != null) env[K_BODY] = body
        if (nick != null) env[K_NICK] = nick
        return env
    }

    fun validate(env: Map<Int, Any?>) {
        require(env.containsKey(K_V)) { "missing envelope key $K_V (version)" }
        require(env.containsKey(K_T)) { "missing envelope key $K_T (type)" }
        require(env.containsKey(K_ID)) { "missing envelope key $K_ID (id)" }
        require(env.containsKey(K_TS)) { "missing envelope key $K_TS (timestamp)" }
        require(env.containsKey(K_SRC)) { "missing envelope key $K_SRC (source)" }

        val v = env[K_V]
        require(v is Int) { "protocol version must be an integer" }
        require(v == RRC_VERSION) { "unsupported version $v" }

        require(env[K_T] is Int) { "message type must be an integer" }
        require(env[K_ID] is ByteArray) { "message id must be bytes" }

        val ts = env[K_TS]
        require(ts is Int || ts is Long) { "timestamp must be an integer" }

        require(env[K_SRC] is ByteArray) { "sender identity must be bytes" }
    }

    private fun generateMessageId(): ByteArray {
        val id = ByteArray(8)
        random.nextBytes(id)
        return id
    }
}
