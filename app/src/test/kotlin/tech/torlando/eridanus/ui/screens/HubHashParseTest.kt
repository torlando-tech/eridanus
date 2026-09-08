// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins down the manual "Enter Hash" dialog's hash parser: a 16-byte
 * (32 hex char) RNS truncated identity hash, tolerating spaces/colons
 * and upper case, and rejecting everything that is not exactly that
 * length.
 */
class HubHashParseTest {
    private val hash16 = ByteArray(16) { (it * 7 + 1).toByte() }
    private val hash32 = hash16.joinToString("") { "%02x".format(it) }

    @Test
    fun plainLowercaseHashParses() {
        assertArrayEquals(hash16, parseHexHash(hash32))
    }

    @Test
    fun uppercaseHashParses() {
        assertArrayEquals(hash16, parseHexHash(hash32.uppercase()))
    }

    @Test
    fun colonSeparatedHashParses() {
        // "aa:bb:cc..." — the form users often copy from other RNS tools.
        val colonForm = hash32.chunked(2).joinToString(":")
        assertArrayEquals(hash16, parseHexHash(colonForm))
    }

    @Test
    fun spaceSeparatedHashParses() {
        // Users paste hashes with spaces (e.g. copied in 4-byte groups) —
        // the parser strips them before the length check.
        val spaceForm = hash32.chunked(4).joinToString(" ")
        assertArrayEquals(hash16, parseHexHash(spaceForm))
    }

    @Test
    fun wrongLengthIsRejected() {
        // Shorter than 16 bytes (a truncated copy from a note) — must not
        // connect to a bogus destination.
        assertNull(parseHexHash(hash32.dropLast(4)))
        // Longer than 16 bytes.
        assertNull(parseHexHash(hash32 + "ab"))
        // A full 32-byte sha256 hex (64 chars) is NOT a valid RNS
        // truncated hash either.
        assertNull(parseHexHash(hash32 + hash32))
    }

    @Test
    fun nonHexCharactersAreRejected() {
        assertNull(parseHexHash(hash32.dropLast(2) + "zz"))
        assertNull(parseHexHash("not a hash at all"))
    }

    @Test
    fun emptyInputIsRejected() {
        assertNull(parseHexHash(""))
    }
}
