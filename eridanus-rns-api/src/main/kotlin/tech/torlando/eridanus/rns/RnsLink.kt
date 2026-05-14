package tech.torlando.eridanus.rns

enum class RnsResourceStrategy { ACCEPT_NONE, ACCEPT_APP, ACCEPT_ALL }

interface RnsLink {
    fun send(payload: ByteArray)
    fun identify(identity: RnsIdentity)
    fun teardown()
    fun getMdu(): Int?
    fun getRemoteIdentity(): RnsIdentity?

    fun setPacketCallback(callback: (data: ByteArray) -> Unit)
    fun setLinkClosedCallback(callback: (RnsLink) -> Unit)
    fun setResourceStrategy(strategy: RnsResourceStrategy)
    fun setResourceCallback(callback: (RnsResourceAdvertisement) -> Boolean)
    fun setResourceConcludedCallback(callback: (RnsResource) -> Unit)
}

interface RnsLinkFactory {
    fun create(
        destination: RnsDestination,
        establishedCallback: (RnsLink) -> Unit,
        closedCallback: (RnsLink) -> Unit,
    ): RnsLink
}
