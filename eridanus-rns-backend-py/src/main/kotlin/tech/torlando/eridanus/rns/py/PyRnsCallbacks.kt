// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns.py

import com.chaquo.python.PyObject

// Bridge callbacks passed across the kotlin↔python seam via event_bridge.py.
// Each Python wrapper in event_bridge invokes `call(...)` on the Kotlin
// instance through chaquopy's reflective method dispatch.
//
// These are deliberately concrete classes, NOT `fun interface`s. A SAM-
// converted lambda (`PyAnnounceCallback { ... }`) compiles to an R8-
// *synthesized* class ($$ExternalSyntheticLambda); R8's name-pattern keep
// rules (`-keep class ...rns.py.** { *; }`) only pin real program classes,
// not R8's own synthetics — so R8 renames the synthetic and the synthetic's
// `call`, and python's `kt_cb.call(...)` then fails with
//   AttributeError: '<minified>' object has no attribute 'call'
// on every received announce / link / packet / resource callback (debug
// builds aren't minified, which masks it). A concrete class is a real
// program class the keep rule actually pins. The single function-type
// constructor param keeps the trailing-lambda ergonomics at call sites.

class PyAnnounceCallback(
    private val fn: (destHash: ByteArray, announcedIdentity: PyObject?, appData: ByteArray?) -> Boolean,
) {
    fun call(destHash: ByteArray, announcedIdentity: PyObject?, appData: ByteArray?): Boolean =
        fn(destHash, announcedIdentity, appData)
}

class PyPacketCallback(private val fn: (data: ByteArray) -> Unit) {
    fun call(data: ByteArray) = fn(data)
}

class PyLinkCallback(private val fn: (link: PyObject) -> Unit) {
    fun call(link: PyObject) = fn(link)
}

class PyResourceCallback(private val fn: (advertisement: PyObject) -> Boolean) {
    fun call(advertisement: PyObject): Boolean = fn(advertisement)
}

class PyResourceConcludedCallback(private val fn: (resource: PyObject) -> Unit) {
    fun call(resource: PyObject) = fn(resource)
}
