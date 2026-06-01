// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.torlando.eridanus.viewmodel.RoomMember

/**
 * Locks in the two pieces of `@`-mention autocomplete logic that are easy to
 * get subtly wrong: [mentionQueryAt]'s cursor-relative token detection (which
 * must fire mid-message yet stay quiet for email-like text and at word
 * boundaries) and [matchingMention]'s prefix filter.
 */
class MentionAutocompleteTest {

    private val members = listOf(
        RoomMember(nick = "alice", hashPrefix = "a1b2c3d4e5f6"),
        RoomMember(nick = "Albert", hashPrefix = "0011223344ff"),
        RoomMember(nick = "bob", hashPrefix = "deadbeef0000"),
        RoomMember(nick = "John Doe", hashPrefix = "feed0000feed"),
        RoomMember(nick = null, hashPrefix = "ca11ab1ecafe"),
    )

    // ── mentionQueryAt ────────────────────────────────────────────────

    @Test
    fun bareAtTriggersWithEmptyPartial() {
        assertEquals(MentionQuery(0, ""), mentionQueryAt("@", 1))
    }

    @Test
    fun partialAfterAtIsCaptured() {
        // "hello @al" with the cursor at the end.
        assertEquals(MentionQuery(6, "al"), mentionQueryAt("hello @al", 9))
    }

    @Test
    fun fireForMentionInMiddleOfMessage() {
        // "hi @bo there" with the cursor right after "bo".
        assertEquals(MentionQuery(3, "bo"), mentionQueryAt("hi @bo there", 6))
    }

    @Test
    fun noTriggerWhenCursorIsAfterAWhitespaceBoundary() {
        // Trailing space: the word under the cursor is empty.
        assertNull(mentionQueryAt("hello @alice ", 13))
    }

    @Test
    fun noTriggerForEmailStyleText() {
        // The word "foo@bar.com" doesn't *start* with '@', so it's not a mention.
        assertNull(mentionQueryAt("mail foo@bar.com", 16))
    }

    @Test
    fun noTriggerForPlainWord() {
        assertNull(mentionQueryAt("hello world", 11))
    }

    @Test
    fun partialIsCursorRelativeNotEndOfText() {
        // "@alice" with the cursor after "al": partial is "al", not "alice".
        assertEquals(MentionQuery(0, "al"), mentionQueryAt("@alice", 3))
    }

    @Test
    fun outOfRangeCursorIsSafe() {
        assertNull(mentionQueryAt("@alice", -1))
        assertNull(mentionQueryAt("@alice", 99))
    }

    // ── matchingMention ───────────────────────────────────────────────

    @Test
    fun emptyPartialMatchesEveryone() {
        assertEquals(members, members.matchingMention(""))
    }

    @Test
    fun prefixMatchIsCaseInsensitive() {
        // "al" → alice + Albert (case-insensitive), not bob.
        assertEquals(
            listOf("alice", "Albert"),
            members.matchingMention("AL").map { it.nick },
        )
    }

    @Test
    fun matchesByHashPrefixWhenNickDoesNot() {
        assertEquals(
            listOf("bob"),
            members.matchingMention("deadbeef").map { it.nick },
        )
    }

    @Test
    fun nicklessMemberMatchesOnHashPrefix() {
        val match = members.matchingMention("ca11")
        assertEquals(1, match.size)
        assertNull(match.single().nick)
    }

    @Test
    fun matchesFirstWordOfMultiWordNick() {
        assertEquals(
            listOf("John Doe"),
            members.matchingMention("john").map { it.nick },
        )
    }
}
