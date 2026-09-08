// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.torlando.eridanus.viewmodel.DiscoveredHub

/**
 * Pins down the hub browser search filter: matches on name OR hash,
 * case-insensitively, with surrounding whitespace ignored, and leaves the
 * list untouched for an empty query.
 */
class HubSearchFilterTest {
    private val hubA = DiscoveredHub(
        hash = ByteArray(32) { 0x11 },
        name = "Aurora Relay",
        starred = true,
    )
    private val hubB = DiscoveredHub(
        hash = ByteArray(32) { 0x22 },
        name = "Borealis",
    )

    private fun names(hubs: List<DiscoveredHub>): List<String> = hubs.map { it.name }

    @Test
    fun emptyQueryReturnsListUnchanged() {
        val input = listOf(hubA, hubB)
        val result = filterHubs(input, "")
        assertEquals(input, result)
        // Blank-only queries are treated as empty too.
        assertEquals(input, filterHubs(input, "   "))
    }

    @Test
    fun nameMatchIsCaseInsensitive() {
        assertEquals(listOf(hubA), filterHubs(listOf(hubA, hubB), "aurora"))
        assertEquals(listOf(hubA), filterHubs(listOf(hubA, hubB), "AURORA RELAY"))
    }

    @Test
    fun nameMatchSurvivesSurroundingWhitespace() {
        assertEquals(listOf(hubB), filterHubs(listOf(hubA, hubB), "  borealis  "))
    }

    @Test
    fun hashPrefixMatchIsCaseInsensitive() {
        // hubB's hash is 32 x 0x22; a prefix typed in uppercase must still
        // match.
        val prefix = hubB.hexHash.take(8)
        assertEquals(listOf(hubB), filterHubs(listOf(hubA, hubB), prefix.uppercase()))
    }

    @Test
    fun queryMatchingOnlyAHashIsStillFound() {
        // No name contains "2222"; the hash branch has to carry the match.
        assertEquals(listOf(hubB), filterHubs(listOf(hubA, hubB), "2222222222"))
    }

    @Test
    fun multipleHubsCanMatch() {
        // A shared substring in both names keeps both, in list order.
        assertEquals(listOf(hubA, hubB), filterHubs(listOf(hubA, hubB), "a"))
    }

    @Test
    fun noMatchReturnsEmptyList() {
        assertTrue(filterHubs(listOf(hubA, hubB), "nonexistent-hub").isEmpty())
        assertTrue(filterHubs(listOf(hubA, hubB), "zz").isEmpty())
    }
}
