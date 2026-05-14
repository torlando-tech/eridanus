// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns.kt

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.link.Link
import tech.torlando.eridanus.rns.RnsDestination
import tech.torlando.eridanus.rns.RnsDestinationDirection
import tech.torlando.eridanus.rns.RnsDestinationFactory
import tech.torlando.eridanus.rns.RnsDestinationType
import tech.torlando.eridanus.rns.RnsIdentity
import tech.torlando.eridanus.rns.RnsLink

class KtRnsDestination(val delegate: Destination) : RnsDestination {
    override val hash: ByteArray get() = delegate.hash
    override val hexHash: String get() = delegate.hexHash

    override fun announce(appData: ByteArray?) {
        delegate.announce(appData)
    }

    override fun setLinkEstablishedCallback(callback: (RnsLink) -> Unit) {
        delegate.setLinkEstablishedCallback { linkAny ->
            val link = linkAny as? Link ?: return@setLinkEstablishedCallback
            callback(KtRnsLink(link))
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is KtRnsDestination && other.delegate === delegate)

    override fun hashCode(): Int = System.identityHashCode(delegate)
}

internal fun RnsDestination.asKt(): Destination = (this as KtRnsDestination).delegate

object KtRnsDestinationFactory : RnsDestinationFactory {
    override fun appAndAspectsFromName(name: String): Pair<String, List<String>>? =
        Destination.appAndAspectsFromName(name)

    override fun create(
        identity: RnsIdentity,
        direction: RnsDestinationDirection,
        type: RnsDestinationType,
        appName: String,
        vararg aspects: String,
    ): RnsDestination {
        val rawDirection = when (direction) {
            RnsDestinationDirection.IN -> DestinationDirection.IN
            RnsDestinationDirection.OUT -> DestinationDirection.OUT
        }
        val rawType = when (type) {
            RnsDestinationType.SINGLE -> DestinationType.SINGLE
            RnsDestinationType.GROUP -> DestinationType.GROUP
            RnsDestinationType.PLAIN -> DestinationType.PLAIN
            RnsDestinationType.LINK -> DestinationType.LINK
        }
        return KtRnsDestination(
            Destination.create(identity.asKt(), rawDirection, rawType, appName, *aspects)
        )
    }
}
