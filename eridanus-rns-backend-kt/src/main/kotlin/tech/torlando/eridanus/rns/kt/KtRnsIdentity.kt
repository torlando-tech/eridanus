package tech.torlando.eridanus.rns.kt

import network.reticulum.identity.Identity
import tech.torlando.eridanus.rns.RnsIdentity
import tech.torlando.eridanus.rns.RnsIdentityFactory

class KtRnsIdentity(val delegate: Identity) : RnsIdentity {
    override val hash: ByteArray get() = delegate.hash
    override fun getPrivateKey(): ByteArray = delegate.getPrivateKey()

    override fun equals(other: Any?): Boolean =
        this === other || (other is KtRnsIdentity && other.delegate === delegate)

    override fun hashCode(): Int = System.identityHashCode(delegate)
}

object KtRnsIdentityFactory : RnsIdentityFactory {
    override fun create(): RnsIdentity = KtRnsIdentity(Identity.create())

    override fun recall(destHash: ByteArray): RnsIdentity? =
        Identity.recall(destHash)?.let(::KtRnsIdentity)

    override fun fromBytes(bytes: ByteArray): RnsIdentity? =
        Identity.fromBytes(bytes)?.let(::KtRnsIdentity)
}

internal fun RnsIdentity.asKt(): Identity = (this as KtRnsIdentity).delegate
