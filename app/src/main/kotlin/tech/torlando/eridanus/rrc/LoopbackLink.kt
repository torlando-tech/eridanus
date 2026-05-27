// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rrc

import tech.torlando.eridanus.rns.RnsIdentity
import tech.torlando.eridanus.rns.RnsLink
import tech.torlando.eridanus.rns.RnsResource
import tech.torlando.eridanus.rns.RnsResourceAdvertisement
import tech.torlando.eridanus.rns.RnsResourceStrategy
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * An in-process [RnsLink] that wires two endpoints together without going
 * through Reticulum at all.
 *
 * Connecting to your *own* hub means linking to a destination this same
 * Reticulum instance hosts. That used to work because Eridanus ran its own
 * full reticulum-kt Transport (shareInstance = true), so the link was a pure
 * in-process loopback that Transport routed correctly. Since a645cf5 ("never
 * act as the shared-instance server") Eridanus is a pure shared-instance
 * *client*, so the same link now has to traverse the external host — which
 * mis-routes the loopback: the hub's reply is delivered back to the hub's own
 * link instead of the client, so the client never gets its WELCOME. Pushing a
 * real link through the network for a same-process conversation is the wrong
 * shape anyway; [pair] instead cross-wires two LoopbackLinks so each end's
 * [send] is delivered straight to the other end's packet callback. Backend-
 * agnostic — restores own-hub connect on both flavors.
 *
 * Deliveries run on one daemon thread per pair, so the two endpoints behave
 * like independent peers (no synchronous re-entrancy back into the sender's
 * call stack — matching how RnsLink callbacks normally arrive off a Reticulum
 * thread).
 */
class LoopbackLink private constructor(
    private val remoteIdentity: RnsIdentity?,
) : RnsLink {
    private var peer: LoopbackLink? = null
    private lateinit var dispatch: java.util.concurrent.ExecutorService
    @Volatile private var closed = false
    private var packetCallback: ((ByteArray) -> Unit)? = null
    private var closedCallback: ((RnsLink) -> Unit)? = null

    private fun safeExec(task: () -> Unit) {
        try {
            dispatch.execute(task)
        } catch (_: RejectedExecutionException) {
            // Dispatcher already shut down by a concurrent teardown — drop.
        }
    }

    override fun send(payload: ByteArray) {
        if (closed) return
        val p = peer ?: return
        val copy = payload.copyOf() // isolate the buffer across the "wire"
        safeExec { if (!p.closed) p.packetCallback?.invoke(copy) }
    }

    // Identity is fixed at construction (the peers already know each other),
    // so RNS's identify round-trip is a no-op over a loopback.
    override fun identify(identity: RnsIdentity) {}

    override fun teardown() {
        if (closed) return
        closed = true
        safeExec { closedCallback?.invoke(this) }
        peer?.teardown()
        // Once both ends are down, stop the shared dispatcher. shutdown()
        // (not shutdownNow) lets the queued close callbacks run first.
        if (peer?.closed == true && !dispatch.isShutdown) dispatch.shutdown()
    }

    // Effectively unbounded MDU so every RRC message fits one packet and the
    // hub never falls back to the Reticulum resource-transfer path (which has
    // no meaning over a loopback).
    override fun getMdu(): Int = 1 shl 20

    override fun getRemoteIdentity(): RnsIdentity? = remoteIdentity

    override fun setPacketCallback(callback: (data: ByteArray) -> Unit) {
        packetCallback = callback
    }

    override fun setLinkClosedCallback(callback: (RnsLink) -> Unit) {
        closedCallback = callback
    }

    // Resources never traverse a loopback (MDU is effectively unbounded).
    override fun setResourceStrategy(strategy: RnsResourceStrategy) {}
    override fun setResourceCallback(callback: (RnsResourceAdvertisement) -> Boolean) {}
    override fun setResourceConcludedCallback(callback: (RnsResource) -> Unit) {}

    companion object {
        /**
         * Create a cross-wired pair. The returned `first` is the client end
         * (its [getRemoteIdentity] reports [hubIdentity]); `second` is the hub
         * end (its [getRemoteIdentity] reports [clientIdentity], so the hub
         * learns the connecting peer's hash without an RNS identify round-trip).
         */
        fun pair(clientIdentity: RnsIdentity?, hubIdentity: RnsIdentity?): Pair<RnsLink, RnsLink> {
            val dispatch = Executors.newSingleThreadExecutor { r ->
                Thread(r, "rrc-loopback").apply { isDaemon = true }
            }
            val clientEnd = LoopbackLink(hubIdentity)
            val hubEnd = LoopbackLink(clientIdentity)
            clientEnd.peer = hubEnd
            hubEnd.peer = clientEnd
            clientEnd.dispatch = dispatch
            hubEnd.dispatch = dispatch
            return clientEnd to hubEnd
        }
    }
}
