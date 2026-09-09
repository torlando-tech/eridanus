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

    /**
     * Register an announce handler.
     *
     * [aspectFilter] is the destination name (e.g. "rrc.hub") the handler
     * cares about. When non-null, the backend only invokes the handler for
     * announces whose destination hash matches
     * `hash_from_name_and_identity(aspectFilter, announcedIdentity)` — the
     * same matching upstream RNS (Transport.py) and reticulum-kt both apply.
     * Passing null delivers EVERY announce on the network to the handler,
     * which is both wasteful and dangerous: app-side code then has to decode
     * arbitrary foreign app_data (LXMF, NomadNet, …), and a malformed length
     * prefix can drive the CBOR decoder into a multi-GB allocation. Always
     * pass the specific aspect unless you genuinely want the firehose.
     *
     * [receivePathResponses] opts the handler in for PATH_RESPONSE-context
     * announces — the one-announce replies a `requestPath()` gets for a
     * destination never seen on the network. Both backends mirror the
     * upstream gate (RNS Transport.py:2502-2504 / reticulum-kt
     * `RichAnnounceHandler.receivePathResponses`, both defaulting to NOT
     * receive them): with the flag false the handler still gets live
     * periodic announces but misses the path response entirely. RRC must
     * opt in — a manually-entered hub hash is ONLY ever announced to us as
     * a path response, and without it the hub never reaches the
     * discovered-hub list / favorites.
     */
    fun registerAnnounceHandler(
        aspectFilter: String?,
        handler: RnsAnnounceHandler,
        receivePathResponses: Boolean = false,
    ): RnsAnnounceHandlerRegistration
}
