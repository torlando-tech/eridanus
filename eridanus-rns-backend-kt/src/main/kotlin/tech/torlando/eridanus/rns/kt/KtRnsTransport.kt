// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns.kt

import network.reticulum.identity.Identity
import network.reticulum.transport.AnnounceHandler
import network.reticulum.transport.RichAnnounceHandler
import network.reticulum.transport.Transport
import tech.torlando.eridanus.rns.RnsAnnounceHandler
import tech.torlando.eridanus.rns.RnsAnnounceHandlerRegistration
import tech.torlando.eridanus.rns.RnsDestination
import tech.torlando.eridanus.rns.RnsTransport

object KtRnsTransport : RnsTransport {
    override fun findDestination(hash: ByteArray): RnsDestination? =
        Transport.findDestination(hash)?.let(::KtRnsDestination)

    override fun requestPath(hash: ByteArray) {
        Transport.requestPath(hash)
    }

    override fun hasPath(hash: ByteArray): Boolean = Transport.hasPath(hash)

    override fun registerDestination(destination: RnsDestination) {
        Transport.registerDestination(destination.asKt())
    }

    override fun deregisterDestination(destination: RnsDestination) {
        Transport.deregisterDestination(destination.asKt())
    }

    override fun registerAnnounceHandler(
        aspectFilter: String?,
        handler: RnsAnnounceHandler,
        receivePathResponses: Boolean,
    ): RnsAnnounceHandlerRegistration {
        // Hold a reference to the exact reticulum-kt handler we register —
        // Transport.deregisterAnnounceHandler keys on object identity, so
        // the returned token closes over `ktHandler`.
        val dispatch = { destinationHash: ByteArray, announcedIdentity: Identity, appData: ByteArray? ->
            handler.onAnnounce(destinationHash, KtRnsIdentity(announcedIdentity), appData)
        }
        val ktHandler: AnnounceHandler = if (receivePathResponses) {
            // reticulum-kt's PATH_RESPONSE gate (Transport.kt:3825-3829)
            // only lets path responses through to a RichAnnounceHandler
            // that opts in via receivePathResponses — the exact mirror of
            // python's handler.receive_path_responses check. Without this
            // a manually-entered hub hash, which is ONLY ever announced to
            // us as a path response, never reaches the app (issue #42).
            object : RichAnnounceHandler {
                override val receivePathResponses: Boolean get() = true
                override fun handleAnnounceWithContext(
                    destinationHash: ByteArray,
                    announcedIdentity: Identity,
                    appData: ByteArray?,
                    hops: Int,
                    receivingInterfaceName: String?,
                    matchedAspect: String?,
                    announcePacketHash: ByteArray?,
                ): Boolean {
                    dispatch(destinationHash, announcedIdentity, appData)
                    return true
                }
            }
        } else {
            AnnounceHandler { destinationHash, announcedIdentity, appData ->
                dispatch(destinationHash, announcedIdentity, appData)
            }
        }
        // aspectFilter scopes delivery to matching destinations only (e.g.
        // "rrc.hub"); reticulum-kt matches it via hashFromNameAndIdentity.
        Transport.registerAnnounceHandler(ktHandler, aspectFilter)
        return RnsAnnounceHandlerRegistration {
            Transport.deregisterAnnounceHandler(ktHandler)
        }
    }
}
