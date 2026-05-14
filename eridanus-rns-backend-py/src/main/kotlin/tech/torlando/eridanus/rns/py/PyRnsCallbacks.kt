package tech.torlando.eridanus.rns.py

import com.chaquo.python.PyObject

// Functional interfaces — passed across the kotlin↔python seam via
// event_bridge.py. Each Python wrapper in event_bridge invokes `call(...)`
// on the Kotlin instance, so chaquopy's java-callable-as-python bridge
// converts kotlin lambdas to python callables of the precise shape
// Reticulum expects.
//
// Defining these as `fun interface` keeps the Kotlin call sites SAM-friendly
// and gives chaquopy a Java functional interface (`@FunctionalInterface`
// under the hood) to introspect.

fun interface PyAnnounceCallback {
    fun call(destHash: ByteArray, announcedIdentity: PyObject?, appData: ByteArray?): Boolean
}

fun interface PyPacketCallback {
    fun call(data: ByteArray)
}

fun interface PyLinkCallback {
    fun call(link: PyObject)
}

fun interface PyResourceCallback {
    fun call(advertisement: PyObject): Boolean
}

fun interface PyResourceConcludedCallback {
    fun call(resource: PyObject)
}
