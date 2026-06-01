// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rrc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import android.content.Context
import org.junit.Test
import tech.torlando.eridanus.rns.RnsBackend
import tech.torlando.eridanus.rns.RnsBackendConfig
import tech.torlando.eridanus.rns.RnsIdentity
import tech.torlando.eridanus.rns.RnsLink
import tech.torlando.eridanus.rns.RnsResource
import tech.torlando.eridanus.rns.RnsResourceAdvertisement
import tech.torlando.eridanus.rns.RnsResourceStrategy
import tech.torlando.eridanus.rrc.RrcConstants.B_WELCOME_CAPS
import tech.torlando.eridanus.rrc.RrcConstants.CAP_ACTION
import tech.torlando.eridanus.rrc.RrcConstants.K_BODY
import tech.torlando.eridanus.rrc.RrcConstants.K_T
import tech.torlando.eridanus.rrc.RrcConstants.T_ACTION
import tech.torlando.eridanus.rrc.RrcConstants.T_ERROR
import tech.torlando.eridanus.rrc.RrcConstants.T_HELLO
import tech.torlando.eridanus.rrc.RrcConstants.T_JOIN
import tech.torlando.eridanus.rrc.RrcConstants.T_MSG
import tech.torlando.eridanus.rrc.RrcConstants.T_WELCOME

/**
 * Locks in RRC `T_ACTION` (type 22) handling against the reference behavior
 * (rrcd 0.3.0+ / NomadNet 1.2.3): ACTION bodies are room content, forwarded
 * verbatim, and — unlike `T_MSG` — are NEVER interpreted as slash commands.
 * Without that gate, an Eridanus-hosted hub would either drop a peer's `/me`
 * action or bounce it back as "unrecognized command".
 *
 * These drive the real [RrcHub] packet path end-to-end through fakes for the
 * (tiny) RNS surface the hub touches, so the assertions exercise production
 * forwarding/command-gating logic, not a reimplementation of it.
 */
class RrcActionTest {

    private class FakeIdentity(override val hash: ByteArray) : RnsIdentity {
        override fun getPrivateKey(): ByteArray = ByteArray(0)
    }

    /** Records everything the hub sends to this link; replays inbound packets into the hub. */
    private class FakeLink(private val remote: RnsIdentity?) : RnsLink {
        val sent = mutableListOf<ByteArray>()
        private var packetCb: ((ByteArray) -> Unit)? = null

        override fun send(payload: ByteArray) { sent.add(payload) }
        override fun identify(identity: RnsIdentity) {}
        override fun teardown() {}
        override fun getMdu(): Int = 1 shl 20
        override fun getRemoteIdentity(): RnsIdentity? = remote
        override fun setPacketCallback(callback: (ByteArray) -> Unit) { packetCb = callback }
        override fun setLinkClosedCallback(callback: (RnsLink) -> Unit) {}
        override fun setResourceStrategy(strategy: RnsResourceStrategy) {}
        override fun setResourceCallback(callback: (RnsResourceAdvertisement) -> Boolean) {}
        override fun setResourceConcludedCallback(callback: (RnsResource) -> Unit) {}

        /** Deliver an inbound packet to the hub, as RNS would. */
        fun feed(payload: ByteArray) { packetCb?.invoke(payload) }

        fun decodedSent(): List<Map<Int, Any?>> = sent.map { RrcCodec.decode(it) }
        fun typesSent(): List<Int?> = decodedSent().map { it[K_T] as? Int }
    }

    /**
     * The hub stores a backend reference but the packet/forward paths under
     * test (acceptLoopbackClient -> onPacket -> handleMessage/handleJoin/
     * sendWelcome) never call into it, so every member errors if touched —
     * any access would be a test-setup bug, not a silent pass.
     */
    private val unusedBackend: RnsBackend = object : RnsBackend {
        override val identifier get() = error("unused")
        override val reticulumVersion get() = error("unused")
        override fun start(context: Context, config: RnsBackendConfig) = error("unused")
        override fun stop(context: Context) = error("unused")
        override val isRunning get() = error("unused")
        override val connectedToSharedInstance get() = error("unused")
        override fun isSharedInstanceRunning(port: Int) = error("unused")
        override suspend fun restart(context: Context, config: RnsBackendConfig) = error("unused")
        override fun setForegroundStatus(text: String) = error("unused")
        override fun setKeepAliveWakeLock(held: Boolean) = error("unused")
        override val identities get() = error("unused")
        override val destinations get() = error("unused")
        override val links get() = error("unused")
        override val resources get() = error("unused")
        override val transport get() = error("unused")
    }

    private fun newHub() = RrcHub(FakeIdentity(ByteArray(16) { 0 }), unusedBackend, "Test Hub")

    /** Attach a link, complete HELLO/WELCOME, and join [room]; returns the link with sent[] cleared. */
    private fun joinedClient(hub: RrcHub, idByte: Int, room: String): FakeLink {
        val link = FakeLink(FakeIdentity(ByteArray(16) { idByte.toByte() }))
        hub.acceptLoopbackClient(link)
        link.feed(RrcCodec.encode(RrcEnvelope.make(T_HELLO, src = link.getRemoteIdentity()!!.hash)))
        link.feed(RrcCodec.encode(RrcEnvelope.make(T_JOIN, src = link.getRemoteIdentity()!!.hash, room = room)))
        link.sent.clear()
        return link
    }

    @Test
    fun actionIsForwardedToRoomMembers() {
        val hub = newHub()
        val alice = joinedClient(hub, 1, "lobby")
        val bob = joinedClient(hub, 2, "lobby")

        alice.feed(RrcCodec.encode(
            RrcEnvelope.make(T_ACTION, src = alice.getRemoteIdentity()!!.hash, room = "lobby", body = "waves")
        ))

        // Bob (the other member) receives the action, with type preserved as T_ACTION.
        val bobAction = bob.decodedSent().firstOrNull { it[K_T] == T_ACTION }
        assertEquals("waves", bobAction?.get(K_BODY))
    }

    @Test
    fun actionWithSlashBodyIsForwardedNotCommandParsed() {
        // The crux: an action body that *looks* like a command ("/me ...", or any
        // leading slash) must be relayed as content, never run through the command
        // handler — matching rrcd's "ACTION bodies are not slash commands" rule.
        val hub = newHub()
        val alice = joinedClient(hub, 1, "lobby")
        val bob = joinedClient(hub, 2, "lobby")

        alice.feed(RrcCodec.encode(
            RrcEnvelope.make(T_ACTION, src = alice.getRemoteIdentity()!!.hash, room = "lobby", body = "/me waves")
        ))

        // Forwarded verbatim to bob...
        val bobAction = bob.decodedSent().firstOrNull { it[K_T] == T_ACTION }
        assertEquals("/me waves", bobAction?.get(K_BODY))
        // ...and alice got NO error back (command handler was not consulted).
        assertNull(
            "action with a slash body must not be rejected as a command",
            alice.typesSent().firstOrNull { it == T_ERROR },
        )
    }

    @Test
    fun regularMessageWithUnknownSlashCommandStillErrors() {
        // Contrast guard: the ACTION exemption is specific to T_ACTION. The same
        // slash body sent as a normal T_MSG is still parsed and rejected — proving
        // the forward-not-parse behavior above comes from the action gate, not a
        // blanket removal of command handling.
        val hub = newHub()
        val alice = joinedClient(hub, 1, "lobby")

        alice.feed(RrcCodec.encode(
            RrcEnvelope.make(T_MSG, src = alice.getRemoteIdentity()!!.hash, room = "lobby", body = "/me waves")
        ))

        assertTrue(
            "unknown slash command in a T_MSG should produce an error",
            alice.typesSent().any { it == T_ERROR },
        )
    }

    @Test
    fun welcomeAdvertisesActionCapability() {
        val hub = newHub()
        val link = FakeLink(FakeIdentity(ByteArray(16) { 1 }))
        hub.acceptLoopbackClient(link)
        link.feed(RrcCodec.encode(RrcEnvelope.make(T_HELLO, src = link.getRemoteIdentity()!!.hash)))

        val welcome = link.decodedSent().firstOrNull { it[K_T] == T_WELCOME }
        @Suppress("UNCHECKED_CAST")
        val caps = (welcome?.get(K_BODY) as? Map<*, *>)?.get(B_WELCOME_CAPS) as? Map<*, *>
        assertEquals(true, caps?.get(CAP_ACTION))
    }

    @Test
    fun actionEnvelopeRoundTripsThroughCodec() {
        val src = ByteArray(16) { 7 }
        val env = RrcEnvelope.make(T_ACTION, src = src, room = "lobby", body = "shrugs", nick = "alice")
        val decoded = RrcCodec.decode(RrcCodec.encode(env))
        assertEquals(T_ACTION, decoded[K_T])
        assertEquals("shrugs", decoded[K_BODY])
        assertEquals("alice", decoded[RrcConstants.K_NICK])
        assertFalse("T_ACTION must be distinct from T_MSG", T_ACTION == T_MSG)
    }
}
