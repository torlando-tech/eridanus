package tech.torlando.eridanus.rns.kt

import network.reticulum.resource.Resource
import network.reticulum.resource.ResourceAdvertisement
import tech.torlando.eridanus.rns.RnsLink
import tech.torlando.eridanus.rns.RnsResource
import tech.torlando.eridanus.rns.RnsResourceAdvertisement
import tech.torlando.eridanus.rns.RnsResourceFactory

class KtRnsResource(val delegate: Resource) : RnsResource {
    override val data: ByteArray? get() = delegate.data
    override val requestId: ByteArray? get() = delegate.requestId
}

class KtRnsResourceAdvertisement(val delegate: ResourceAdvertisement) : RnsResourceAdvertisement {
    override val requestId: ByteArray? get() = delegate.requestId
}

object KtRnsResourceFactory : RnsResourceFactory {
    override fun create(
        data: ByteArray,
        link: RnsLink,
        advertise: Boolean,
        autoCompress: Boolean,
        requestId: ByteArray?,
    ): RnsResource = KtRnsResource(
        Resource.create(
            data = data,
            link = link.asKt(),
            advertise = advertise,
            autoCompress = autoCompress,
            requestId = requestId,
        )
    )
}
