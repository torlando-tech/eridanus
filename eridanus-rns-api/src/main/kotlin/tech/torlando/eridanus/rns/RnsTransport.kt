// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns

fun interface RnsAnnounceHandler {
    fun onAnnounce(destHash: ByteArray, announcedIdentity: RnsIdentity?, appData: ByteArray?): Boolean
}

/**
 * Token returned by [RnsTransport.registerAnnounceHandler]. Holding code
 * MUST call [deregister] when the handler should stop receiving announces
 * — e.g. before re-registering, and when the registering component is
 * torn down. Without it, handlers accumulate in the underlying RNS
 * Transport's handler list (each pinning whatever the handler lambda
 * captured), because RNS keys deregistration on the exact handler object
 * the backend wrapped — which only the backend still holds.
 *
 * [deregister] is idempotent and safe to call even after the underlying
 * RNS instance has been restarted out from under it — the stale handler
 * simply won't be found.
 */
fun interface RnsAnnounceHandlerRegistration {
    fun deregister()
}

interface RnsTransport {
    fun findDestination(hash: ByteArray): RnsDestination?
    fun requestPath(hash: ByteArray)
    fun hasPath(hash: ByteArray): Boolean

    fun registerDestination(destination: RnsDestination)
    fun deregisterDestination(destination: RnsDestination)
    fun registerAnnounceHandler(handler: RnsAnnounceHandler): RnsAnnounceHandlerRegistration
}
