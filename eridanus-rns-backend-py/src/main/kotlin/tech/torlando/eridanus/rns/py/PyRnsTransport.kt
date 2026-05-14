package tech.torlando.eridanus.rns.py

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import tech.torlando.eridanus.rns.RnsAnnounceHandler
import tech.torlando.eridanus.rns.RnsDestination
import tech.torlando.eridanus.rns.RnsTransport

class PyRnsTransport(private val rns: PyObject) : RnsTransport {

    override fun findDestination(hash: ByteArray): RnsDestination? {
        // Reticulum doesn't expose Transport.find_destination(hash). The
        // bridge runs a linear scan over Transport.destinations (always
        // O(1) in practice).
        val py = bridge.callAttr("find_local_destination", hash.toPyBytes()) ?: return null
        return PyRnsDestination(py)
    }

    override fun requestPath(hash: ByteArray) {
        rns.get("Transport")!!.callAttr("request_path", hash.toPyBytes())
    }

    override fun hasPath(hash: ByteArray): Boolean =
        rns.get("Transport")!!.callAttr("has_path", hash.toPyBytes())
            .toJava(Boolean::class.javaObjectType)

    override fun registerDestination(destination: RnsDestination) {
        rns.get("Transport")!!.callAttr("register_destination", destination.asPy())
    }

    override fun deregisterDestination(destination: RnsDestination) {
        rns.get("Transport")!!.callAttr("deregister_destination", destination.asPy())
    }

    override fun registerAnnounceHandler(handler: RnsAnnounceHandler) {
        val ktCb = PyAnnounceCallback { destHash, announcedIdentity, appData ->
            // The PyObject for the announced identity might be useful to
            // app-side handlers; eridanus' announce path ignores it but
            // expose via PyRnsIdentity so the seam stays parametric.
            val identityWrap = announcedIdentity?.let(::PyRnsIdentity)
            handler.onAnnounce(destHash, identityWrap, appData)
        }
        val pyHandler = bridge.callAttr("announce_handler", ktCb)
        rns.get("Transport")!!.callAttr("register_announce_handler", pyHandler)
    }

    private val bridge: PyObject
        get() = Python.getInstance().getModule("event_bridge")
}
