// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns.py

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import tech.torlando.eridanus.rns.RnsDestination
import tech.torlando.eridanus.rns.RnsDestinationDirection
import tech.torlando.eridanus.rns.RnsDestinationFactory
import tech.torlando.eridanus.rns.RnsDestinationType
import tech.torlando.eridanus.rns.RnsIdentity
import tech.torlando.eridanus.rns.RnsLink

class PyRnsDestination(val delegate: PyObject) : RnsDestination {
    override val hash: ByteArray
        get() = delegate.get("hash")!!.toJava(ByteArray::class.java)

    override val hexHash: String
        get() = delegate.get("hexhash")!!.toString()

    override fun announce(appData: ByteArray?) {
        delegate.callAttr("announce", appData?.toPyBytes())
    }

    override fun setLinkEstablishedCallback(callback: (RnsLink) -> Unit) {
        val ktCb = PyLinkCallback { linkPy -> callback(PyRnsLink(linkPy)) }
        val pyCallable = bridge.callAttr("link_callback", ktCb)
        delegate.callAttr("set_link_established_callback", pyCallable)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is PyRnsDestination && other.delegate == delegate)

    override fun hashCode(): Int = delegate.hashCode()

    companion object {
        // Cache the bridge module so the announce-handler path doesn't pay
        // for a module lookup on every call.
        internal val bridge: PyObject
            get() = Python.getInstance().getModule("event_bridge")
    }
}

internal fun RnsDestination.asPy(): PyObject = (this as PyRnsDestination).delegate

class PyRnsDestinationFactory(private val rns: PyObject) : RnsDestinationFactory {
    override fun appAndAspectsFromName(name: String): Pair<String, List<String>>? {
        val result = rns.get("Destination")!!.callAttr("app_and_aspects_from_name", name)
            ?: return null
        val items = result.asList()
        val appName = items[0].toString()
        val aspects = items[1].asList().map { it.toString() }
        return appName to aspects
    }

    override fun create(
        identity: RnsIdentity,
        direction: RnsDestinationDirection,
        type: RnsDestinationType,
        appName: String,
        vararg aspects: String,
    ): RnsDestination {
        val rawDirection = when (direction) {
            RnsDestinationDirection.IN -> 0x11
            RnsDestinationDirection.OUT -> 0x12
        }
        val rawType = when (type) {
            RnsDestinationType.SINGLE -> 0x00
            RnsDestinationType.GROUP -> 0x01
            RnsDestinationType.PLAIN -> 0x02
            RnsDestinationType.LINK -> 0x03
        }
        // Reticulum's Destination is variadic: `Destination(identity,
        // direction, type, app_name, *aspects)`. callAttr accepts varargs,
        // so build a flat positional list.
        val args = mutableListOf<Any?>(identity.asPy(), rawDirection, rawType, appName)
        args.addAll(aspects)
        val dest = rns.callAttrThrows("Destination", *args.toTypedArray())
        return PyRnsDestination(dest)
    }
}
