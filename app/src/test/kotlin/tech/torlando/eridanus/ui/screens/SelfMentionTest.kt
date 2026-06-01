// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins down the matching rules for self-mention highlighting: case-insensitive,
 * word-boundary guarded (so it neither over-matches `@bobby` nor fires on the
 * `@` in an email), literal (regex metacharacters escaped), and multi-word.
 */
class SelfMentionTest {

    @Test
    fun matchesPlainMention() {
        // "@bob" spans indices 4..7.
        assertEquals(listOf(4..7), selfMentionRanges("hey @bob", "bob"))
    }

    @Test
    fun matchIsCaseInsensitive() {
        assertEquals(listOf(0..3), selfMentionRanges("@BOB rocks", "bob"))
        assertEquals(listOf(4..7), selfMentionRanges("hey @bob", "BOB"))
    }

    @Test
    fun doesNotMatchInsideLongerName() {
        assertEquals(emptyList<IntRange>(), selfMentionRanges("@bobby", "bob"))
    }

    @Test
    fun matchStopsAtPunctuation() {
        // Trailing '!' is not part of the highlighted mention.
        assertEquals(listOf(0..3), selfMentionRanges("@bob! hi", "bob"))
    }

    @Test
    fun doesNotMatchEmailLikeText() {
        // '@' preceded by a word char (an email local part) is not a mention.
        assertEquals(emptyList<IntRange>(), selfMentionRanges("write me@bob.net", "bob"))
    }

    @Test
    fun matchesMultipleOccurrences() {
        assertEquals(listOf(0..3, 9..12), selfMentionRanges("@bob and @bob", "bob"))
    }

    @Test
    fun matchesMultiWordNick() {
        // The space inside the nick is matched literally.
        assertEquals(listOf(3..11), selfMentionRanges("hi @John Doe!", "John Doe"))
    }

    @Test
    fun escapesRegexMetacharactersInNick() {
        // The '.' must match a literal dot, not any character.
        assertEquals(listOf(0..3), selfMentionRanges("@a.b here", "a.b"))
        assertEquals(emptyList<IntRange>(), selfMentionRanges("@axb here", "a.b"))
    }

    @Test
    fun blankNickMatchesNothing() {
        assertEquals(emptyList<IntRange>(), selfMentionRanges("@bob hi", ""))
        assertEquals(emptyList<IntRange>(), selfMentionRanges("@bob hi", "   "))
    }

    @Test
    fun nickTrimmedBeforeMatching() {
        assertEquals(listOf(4..7), selfMentionRanges("hey @bob", "  bob  "))
    }

    @Test
    fun noMentionWhenNickAbsent() {
        assertEquals(emptyList<IntRange>(), selfMentionRanges("just a normal line", "bob"))
    }
}
