package tech.torlando.eridanus.rns.py

import com.chaquo.python.Kwarg
import com.chaquo.python.PyObject
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
        // _no_use=True skips Identity.recall's side-effect call to
        // RNS.Reticulum._used_destination_data, which in shared-instance-
        // client mode reaches over to the host via
        // multiprocessing.connection — and that channel needs a pre-shared
        // rpc_key the user would otherwise have to paste in from Sideband.
        // The side-effect is purely an LRU-touch on the host's
        // known_destinations cache; skipping it just means the host might
        // evict the cached identity if we leave the app idle long enough
        // that no other client uses the destination, in which case the
        // next connect re-requests a path (~1s overhead, negligible).
        // The whole RNS data plane (Link, Packet, Transport.request_path,
        // announce handlers, Resource transfer) goes over the
        // LocalClientInterface socket and never touches the RPC channel —
        // see Reticulum.py for the call sites of get_rpc_client().
        rns.get("Identity")!!
            .callAttr("recall", destHash.toPyBytes(), Kwarg("_no_use", true))
            ?.let(::PyRnsIdentity)

    override fun fromBytes(bytes: ByteArray): RnsIdentity? {
        // Reticulum's Identity has no public from_bytes — it loads via
        // load_private_key, which mutates an existing instance. Pattern:
        // Identity(create_keys=False) is cheap (no fresh keypair) then
        // load_private_key overwrites the in-memory keys with the saved
        // bytes.
        return try {
            val identity = rns.callAttr("Identity", Kwarg("create_keys", false))
            identity.callAttr("load_private_key", bytes.toPyBytes())
            PyRnsIdentity(identity)
        } catch (_: Throwable) {
            null
        }
    }
}

internal fun RnsIdentity.asPy(): PyObject = (this as PyRnsIdentity).delegate
