package tech.torlando.eridanus.rns.kt

import network.reticulum.link.Link
import network.reticulum.resource.Resource
import network.reticulum.resource.ResourceAdvertisement
import tech.torlando.eridanus.rns.RnsIdentity
import tech.torlando.eridanus.rns.RnsLink
import tech.torlando.eridanus.rns.RnsResource
import tech.torlando.eridanus.rns.RnsResourceAdvertisement
import tech.torlando.eridanus.rns.RnsResourceStrategy

class KtRnsLink(val delegate: Link) : RnsLink {
    override fun send(payload: ByteArray) {
        delegate.send(payload)
    }

    override fun identify(identity: RnsIdentity) {
        delegate.identify(identity.asKt())
    }

    override fun teardown() {
        delegate.teardown()
    }

    override fun getMdu(): Int? = delegate.getMdu()

    override fun getRemoteIdentity(): RnsIdentity? =
        delegate.getRemoteIdentity()?.let(::KtRnsIdentity)

    override fun setPacketCallback(callback: (data: ByteArray) -> Unit) {
        delegate.setPacketCallback { data, _ -> callback(data) }
    }

    override fun setLinkClosedCallback(callback: (RnsLink) -> Unit) {
        delegate.setLinkClosedCallback { closedLink -> callback(KtRnsLink(closedLink)) }
    }

    override fun setResourceStrategy(strategy: RnsResourceStrategy) {
        val rawStrategy = when (strategy) {
            RnsResourceStrategy.ACCEPT_NONE -> Link.ACCEPT_NONE
            RnsResourceStrategy.ACCEPT_APP -> Link.ACCEPT_APP
            RnsResourceStrategy.ACCEPT_ALL -> Link.ACCEPT_ALL
        }
        delegate.setResourceStrategy(rawStrategy)
    }

    override fun setResourceCallback(callback: (RnsResourceAdvertisement) -> Boolean) {
        delegate.setResourceCallback { advertisement: ResourceAdvertisement ->
            callback(KtRnsResourceAdvertisement(advertisement))
        }
    }

    override fun setResourceConcludedCallback(callback: (RnsResource) -> Unit) {
        delegate.callbacks.resourceConcluded = handler@{ resourceObj ->
            val res = resourceObj as? Resource ?: return@handler
            callback(KtRnsResource(res))
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is KtRnsLink && other.delegate === delegate)

    override fun hashCode(): Int = System.identityHashCode(delegate)
}

internal fun RnsLink.asKt(): Link = (this as KtRnsLink).delegate
