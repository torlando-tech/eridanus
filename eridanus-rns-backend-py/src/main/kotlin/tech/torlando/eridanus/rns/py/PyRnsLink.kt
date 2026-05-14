// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns.py

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import tech.torlando.eridanus.rns.RnsDestination
import tech.torlando.eridanus.rns.RnsIdentity
import tech.torlando.eridanus.rns.RnsLink
import tech.torlando.eridanus.rns.RnsLinkFactory
import tech.torlando.eridanus.rns.RnsResource
import tech.torlando.eridanus.rns.RnsResourceAdvertisement
import tech.torlando.eridanus.rns.RnsResourceStrategy

class PyRnsLink(val delegate: PyObject) : RnsLink {

    override fun send(payload: ByteArray) {
        // Reticulum has no direct `link.send(bytes)` — the pattern is
        // `Packet(link, data).send()`. Wrap that here so the seam keeps
        // the friendlier signature.
        val rns = rnsModule
        val packet = rns.callAttr("Packet", delegate, payload.toPyBytes())
        packet.callAttr("send")
    }

    override fun identify(identity: RnsIdentity) {
        delegate.callAttr("identify", identity.asPy())
    }

    override fun teardown() {
        delegate.callAttr("teardown")
    }

    override fun getMdu(): Int? =
        delegate.callAttr("get_mdu")?.toJava(Int::class.javaObjectType)

    override fun getRemoteIdentity(): RnsIdentity? =
        delegate.callAttr("get_remote_identity")?.let(::PyRnsIdentity)

    override fun setPacketCallback(callback: (data: ByteArray) -> Unit) {
        val ktCb = PyPacketCallback { data -> callback(data) }
        val pyCallable = bridge.callAttr("packet_callback", ktCb)
        delegate.callAttr("set_packet_callback", pyCallable)
    }

    override fun setLinkClosedCallback(callback: (RnsLink) -> Unit) {
        val ktCb = PyLinkCallback { linkPy -> callback(PyRnsLink(linkPy)) }
        val pyCallable = bridge.callAttr("link_callback", ktCb)
        delegate.callAttr("set_link_closed_callback", pyCallable)
    }

    override fun setResourceStrategy(strategy: RnsResourceStrategy) {
        val raw = when (strategy) {
            RnsResourceStrategy.ACCEPT_NONE -> 0x00
            RnsResourceStrategy.ACCEPT_APP -> 0x01
            RnsResourceStrategy.ACCEPT_ALL -> 0x02
        }
        delegate.callAttr("set_resource_strategy", raw)
    }

    override fun setResourceCallback(callback: (RnsResourceAdvertisement) -> Boolean) {
        val ktCb = PyResourceCallback { advPy ->
            callback(PyRnsResourceAdvertisement(advPy))
        }
        val pyCallable = bridge.callAttr("resource_callback", ktCb)
        delegate.callAttr("set_resource_callback", pyCallable)
    }

    override fun setResourceConcludedCallback(callback: (RnsResource) -> Unit) {
        val ktCb = PyResourceConcludedCallback { resPy ->
            callback(PyRnsResource(resPy))
        }
        val pyCallable = bridge.callAttr("resource_concluded_callback", ktCb)
        delegate.callAttr("set_resource_concluded_callback", pyCallable)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is PyRnsLink && other.delegate == delegate)

    override fun hashCode(): Int = delegate.hashCode()

    companion object {
        internal val bridge: PyObject
            get() = Python.getInstance().getModule("event_bridge")
    }
}

internal fun RnsLink.asPy(): PyObject = (this as PyRnsLink).delegate

class PyRnsLinkFactory(private val rns: PyObject) : RnsLinkFactory {
    override fun create(
        destination: RnsDestination,
        establishedCallback: (RnsLink) -> Unit,
        closedCallback: (RnsLink) -> Unit,
    ): RnsLink {
        val bridge = Python.getInstance().getModule("event_bridge")
        val establishedKt = PyLinkCallback { linkPy -> establishedCallback(PyRnsLink(linkPy)) }
        val closedKt = PyLinkCallback { linkPy -> closedCallback(PyRnsLink(linkPy)) }
        val establishedPy = bridge.callAttr("link_callback", establishedKt)
        val closedPy = bridge.callAttr("link_callback", closedKt)
        val link = rns.callAttr("Link", destination.asPy(), establishedPy, closedPy)
        return PyRnsLink(link)
    }
}
