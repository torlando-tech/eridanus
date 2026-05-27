// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns.py

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import tech.torlando.eridanus.rns.RnsAnnounceHandler
import tech.torlando.eridanus.rns.RnsAnnounceHandlerRegistration
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
        // Upstream RNS registers a destination inside Destination.__init__
        // (Destination.py:196), and Transport.register_destination then
        // raises on the redundant re-register of an IN destination:
        //   KeyError: 'Attempt to register an already registered destination.'
        // reticulum-kt's Transport.registerDestination is idempotent (it
        // no-ops when the hash is already registered — Transport.kt:936), and
        // the shared RrcHub.start() relies on that contract: it calls
        // destinations.create() (which already registers) and *then*
        // registerDestination(). Without mirroring the idempotent contract
        // here, hub hosting crashes on start on the python flavor and the
        // Host toggle silently does nothing. See find_local_destination in
        // event_bridge.py for the hash scan backing findDestination().
        if (findDestination(destination.hash) != null) return
        rns.get("Transport")!!.callAttr("register_destination", destination.asPy())
    }

    override fun deregisterDestination(destination: RnsDestination) {
        rns.get("Transport")!!.callAttr("deregister_destination", destination.asPy())
    }

    override fun registerAnnounceHandler(
        handler: RnsAnnounceHandler,
    ): RnsAnnounceHandlerRegistration {
        val ktCb = PyAnnounceCallback { destHash, announcedIdentity, appData ->
            // The PyObject for the announced identity might be useful to
            // app-side handlers; eridanus' announce path ignores it but
            // expose via PyRnsIdentity so the seam stays parametric.
            val identityWrap = announcedIdentity?.let(::PyRnsIdentity)
            handler.onAnnounce(destHash, identityWrap, appData)
        }
        // event_bridge.announce_handler wraps the kotlin callback in a python
        // object with the aspect_filter / received_announce shape RNS wants.
        // RNS.Transport.deregister_announce_handler keys on that exact object,
        // so the returned token closes over `pyHandler`.
        val pyHandler = bridge.callAttr("announce_handler", ktCb)
        rns.get("Transport")!!.callAttr("register_announce_handler", pyHandler)
        return RnsAnnounceHandlerRegistration {
            rns.get("Transport")!!.callAttr("deregister_announce_handler", pyHandler)
        }
    }

    private val bridge: PyObject
        get() = Python.getInstance().getModule("event_bridge")
}
