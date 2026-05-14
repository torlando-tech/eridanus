package tech.torlando.eridanus.rns.py

import com.chaquo.python.Kwarg
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import tech.torlando.eridanus.rns.RnsIdentity
import tech.torlando.eridanus.rns.RnsIdentityFactory

class PyRnsIdentity(val delegate: PyObject) : RnsIdentity {
    override val hash: ByteArray
        get() = delegate.get("hash")!!.toJava(ByteArray::class.java)

    override fun getPrivateKey(): ByteArray =
        delegate.callAttr("get_private_key").toJava(ByteArray::class.java)

    override fun equals(other: Any?): Boolean =
        this === other || (other is PyRnsIdentity && other.delegate == delegate)

    override fun hashCode(): Int = delegate.hashCode()
}

class PyRnsIdentityFactory(private val rns: PyObject) : RnsIdentityFactory {
    override fun create(): RnsIdentity =
        PyRnsIdentity(rns.callAttr("Identity"))

    override fun recall(destHash: ByteArray): RnsIdentity? =
        rns.get("Identity")!!.callAttr("recall", destHash)?.let(::PyRnsIdentity)

    override fun fromBytes(bytes: ByteArray): RnsIdentity? {
        // Reticulum's Identity has no public from_bytes — it loads via
        // load_private_key, which mutates an existing instance. Pattern:
        // Identity(create_keys=False) is cheap (no fresh keypair) then
        // load_private_key overwrites the in-memory keys with the saved
        // bytes.
        return try {
            val identity = rns.callAttr("Identity", Kwarg("create_keys", false))
            identity.callAttr("load_private_key", bytes)
            PyRnsIdentity(identity)
        } catch (_: Throwable) {
            null
        }
    }
}

internal fun RnsIdentity.asPy(): PyObject = (this as PyRnsIdentity).delegate

internal val rnsModule: PyObject
    get() = Python.getInstance().getModule("RNS")
