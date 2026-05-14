// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rns.py

import com.chaquo.python.PyObject
import com.chaquo.python.Python

// Module-scoped chaquopy interop helpers shared by every PyRns* wrapper.
// Lives here (not on any specific wrapper) because nothing about these
// helpers is Identity- / Destination- / Link- specific — they're how
// kotlin code reaches into the embedded python interpreter at all.

/**
 * The RNS top-level module as a PyObject. Cached lookup target for every
 * factory / wrapper that needs to call upstream RNS classes.
 */
internal val rnsModule: PyObject
    get() = Python.getInstance().getModule("RNS")

/**
 * Wrap a Kotlin ByteArray in a real Python `bytes` PyObject before passing
 * it across the chaquopy boundary into RNS.
 *
 * Chaquopy's documented mapping is Java byte[] ↔ Python bytes, but on
 * chaquopy 17 + Python 3.12 a byte[] handed directly to `callAttr(...)`
 * arrives as a Java byte[] proxy object that supports iteration / indexing
 * but isn't a real `bytes`. RNS uses `bytes + X` concatenation in dozens
 * of hot paths (Packet.pack, Identity ratchet derivation, Link handshake
 * digest computation, …), and `bytes + proxy` raises
 * `TypeError: can't concat list to bytes`. Constructing a real `bytes`
 * via `builtins.bytes(byteArray)` produces a Python object RNS treats
 * uniformly. Cheap — chaquopy does the byte-by-byte copy once at the
 * boundary.
 *
 * Use this at every Kotlin call site that hands a ByteArray to a Python
 * RNS method. We deliberately keep the wrapping on the Kotlin side
 * rather than adding python-side facade functions for each call —
 * event_bridge.py stays minimal.
 */
internal fun ByteArray.toPyBytes(): PyObject =
    Python.getInstance().builtins.callAttr("bytes", this)
