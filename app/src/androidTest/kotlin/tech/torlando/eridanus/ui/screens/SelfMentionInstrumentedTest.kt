// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs [selfMentionRanges] on the **device's** regex engine.
 *
 * The JVM unit tests in `SelfMentionTest` can't catch Android-only regex gaps:
 * an earlier `(?U)` flag compiled fine on the desktop JVM but threw
 * `PatternSyntaxException` on Android's ICU engine, crashing every message
 * render. This pins the pattern (and its Unicode word-boundary class) to the
 * real engine so that whole class of regression can't return silently.
 */
@RunWith(AndroidJUnit4::class)
class SelfMentionInstrumentedTest {

    @Test
    fun patternCompilesAndMatchesOnDeviceRegexEngine() {
        // Plain ASCII match.
        assertEquals(listOf(4..7), selfMentionRanges("hey @bob", "bob"))
        // Unicode word-boundary guard — the part that needs \p{L}, not \w.
        assertEquals(emptyList<IntRange>(), selfMentionRanges("@bobé", "bob"))
        assertEquals(emptyList<IntRange>(), selfMentionRanges("ý@bob", "bob"))
        assertEquals(listOf(4..8), selfMentionRanges("hey @josé!", "josé"))
        // The default-nick path that crashed in the field — must not throw.
        selfMentionRanges("hello world", "Anonymous Peer")
    }
}
