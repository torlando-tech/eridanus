// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns.kt

import network.reticulum.transport.AnnounceHandler
import network.reticulum.transport.Transport
import tech.torlando.eridanus.rns.RnsAnnounceHandler
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

    override fun registerAnnounceHandler(handler: RnsAnnounceHandler) {
        Transport.registerAnnounceHandler(
            AnnounceHandler { destinationHash, announcedIdentity, appData ->
                handler.onAnnounce(destinationHash, KtRnsIdentity(announcedIdentity), appData)
            }
        )
    }
}
