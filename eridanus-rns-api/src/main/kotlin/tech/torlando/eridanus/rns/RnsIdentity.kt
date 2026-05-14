package tech.torlando.eridanus.rns

interface RnsIdentity {
    val hash: ByteArray
    fun getPrivateKey(): ByteArray
}

interface RnsIdentityFactory {
    fun create(): RnsIdentity
    fun recall(destHash: ByteArray): RnsIdentity?
    fun fromBytes(bytes: ByteArray): RnsIdentity?
}
