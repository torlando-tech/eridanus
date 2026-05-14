package tech.torlando.eridanus.rns

interface RnsResourceAdvertisement {
    val requestId: ByteArray?
}

interface RnsResource {
    val data: ByteArray?
    val requestId: ByteArray?
}

interface RnsResourceFactory {
    fun create(
        data: ByteArray,
        link: RnsLink,
        advertise: Boolean = true,
        autoCompress: Boolean = false,
        requestId: ByteArray? = null,
    ): RnsResource
}
