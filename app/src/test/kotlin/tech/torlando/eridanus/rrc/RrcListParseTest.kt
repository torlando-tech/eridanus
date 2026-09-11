// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.rrc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.torlando.eridanus.viewmodel.AvailableRoom

/**
 * Locks in parsing of rrcd `/list` room-list notices against the two shapes
 * observed in the wild:
 *
 * 1. Multi-line single NOTICE (rrcd 0.3.x, Eridanus's own hub):
 *    "Registered public rooms:\n  lobby - General chat\n  linux"
 *
 * 2. Per-room NOTICEs (rnscommunity hub 28c7c1a6..., 2026-09): the header
 *    arrives alone and each room follows as its own indented NOTICE. The
 *    replay tests use the exact bodies captured from that hub's logs (via
 *    nomadnet's raw NOTICE log) on 2026-09-11, and drive them through
 *    RoomListAccumulator.feed() — the same production entry point the
 *    ViewModel uses — asserting every room of the list comes through.
 */
class RrcListParseTest {

    private fun room(name: String, topic: String? = null) = AvailableRoom(name = name, topic = topic)

    // ---------------------------------------------------------------- headers

    @Test
    fun `header standalone is a list header`() {
        assertTrue(RrcListParse.isListHeader("Registered public rooms:"))
        assertTrue(RrcListParse.isListHeader("  Registered public rooms:"))
    }

    @Test
    fun `multi-line notice whose first line is the header is a list header`() {
        val body = "Registered public rooms:\n  lobby - General chat\n  linux"
        assertTrue(RrcListParse.isListHeader(body))
    }

    @Test
    fun `legacy nomadnet header is recognized`() {
        assertTrue(RrcListParse.isListHeader("Rooms:"))
        assertTrue(RrcListParse.isListHeader("Rooms:\n  lobby (2 members) - General chat"))
    }

    @Test
    fun `non-header notices are not list headers`() {
        assertFalse(RrcListParse.isListHeader("Welcome to the RNS Community RRC hub."))
        assertFalse(RrcListParse.isListHeader("  general"))
        assertFalse(RrcListParse.isListHeader("Registered public rooms"))
        assertFalse(RrcListParse.isListHeader(""))
    }

    @Test
    fun `no-rooms replies recognized`() {
        assertTrue(RrcListParse.isNoRooms("No public rooms registered"))
        assertTrue(RrcListParse.isNoRooms("  no rooms  "))
        assertFalse(RrcListParse.isNoRooms("no rooms in lobby"))
    }

    // ------------------------------------------------------------- room lines

    @Test
    fun `indented room line without topic`() {
        assertEquals(room("linux"), RrcListParse.parseRoomLine("  linux"))
        assertEquals(room("general"), RrcListParse.parseRoomLine("   general"))
    }

    @Test
    fun `indented room line with topic`() {
        assertEquals(
            room("catfacts", "Cat Facts! Type !catfact for a fact about cats!"),
            RrcListParse.parseRoomLine("  catfacts - Cat Facts! Type !catfact for a fact about cats!"),
        )
    }

    @Test
    fun `topic containing separator keeps first dash as split`() {
        // Real rnscommunity line: the room's own topic contains " - ".
        assertEquals(
            room("loelinverse", "- Etelectic Experiences through Electronic Explorations"),
            RrcListParse.parseRoomLine("  loelinverse - - Etelectic Experiences through Electronic Explorations"),
        )
    }

    @Test
    fun `unindented and blank lines are not room lines`() {
        assertNull(RrcListParse.parseRoomLine("general"))
        assertNull(RrcListParse.parseRoomLine(""))
        assertNull(RrcListParse.parseRoomLine("   "))
    }

    @Test
    fun `legacy member count stripped only in legacy mode`() {
        assertEquals(
            room("lobby", "General chat"),
            RrcListParse.parseRoomLine("  lobby (2 members) - General chat", legacyNomadnet = true),
        )
        // In modern mode "(N members)" is ordinary topic text — preserved.
        assertEquals(
            room("lobby", "General chat (2 members)"),
            RrcListParse.parseRoomLine("  lobby - General chat (2 members)"),
        )
    }

    @Test
    fun `line with empty name is not a room line`() {
        // "- lonely" → name part is empty → reject.
        assertNull(RrcListParse.parseRoomLine("  - lonely"))
    }

    // --------------------------------------------------------- multi-line form

    @Test
    fun `multi-line list notice parses all rooms`() {
        val body = "Registered public rooms:\n  lobby - General chat\n  linux"
        assertEquals(
            listOf(room("lobby", "General chat"), room("linux")),
            RrcListParse.parseListNotice(body),
        )
    }

    @Test
    fun `header with no room lines is not a multi-line list`() {
        assertNull(RrcListParse.parseListNotice("Registered public rooms:"))
        assertNull(RrcListParse.parseListNotice("Registered public rooms:\n  "))
    }

    @Test
    fun `unrelated multi-line text is not a list`() {
        assertNull(RrcListParse.parseListNotice("hello\n  world"))
    }

    // ------------------------------------------- per-room accumulation (shape 2)

    /**
     * The exact roomless NOTICE sequence captured from the rnscommunity hub
     * (28c7c1a68c735693aa8e6b8193ed44b2) on 2026-09-11, verbatim from the
     * raw NOTICE log (nomadnet's `_parse_room_list_notice input:` lines), in
     * wire order: WELCOME greeting, header, then one notice per room.
     */
    private val rnsCommunitySequence = listOf(
        "Welcome to the RNS Community RRC hub. /join general for the main room!",
        "Registered public rooms:",
        "  catfacts - Cat Facts! Type !catfact for a fact about cats!",
        "  chat-hispano",
        "  loelinverse - - Etelectic Experiences through Electronic Explorations",
        "  nordisk - \"Generel nordisksproget snak om alt muligt\"",
        "  retibooks - \"Visit RetiBooks on c388d720f56483a8dc8668ee5bea3577:/page/index.mu\"",
        "  general",
        "  n2ycr - sunday 16:00 EDT weekly net - ham radio club",
        "  linux",
        "  reticulumberlin",
        "  rrcbot - 4f5f1c043e0c11e1c156c1bfc922dfbd:/page/repo.mu",
    )

    private val expectedRnsRooms = listOf(
        room("catfacts", "Cat Facts! Type !catfact for a fact about cats!"),
        room("chat-hispano"),
        room("loelinverse", "- Etelectic Experiences through Electronic Explorations"),
        room("nordisk", "\"Generel nordisksproget snak om alt muligt\""),
        room("retibooks", "\"Visit RetiBooks on c388d720f56483a8dc8668ee5bea3577:/page/index.mu\""),
        room("general"),
        room("n2ycr", "sunday 16:00 EDT weekly net - ham radio club"),
        room("linux"),
        room("reticulumberlin"),
        room("rrcbot", "4f5f1c043e0c11e1c156c1bfc922dfbd:/page/repo.mu"),
    )

    private fun makeAccumulator() = RoomListAccumulator()

    /** Feeds the captured rnscommunity sequence (post-header) into [acc]. */
    private fun feedRnsRooms(acc: RoomListAccumulator, clock: Long) {
        acc.start(rnsCommunitySequence[1], clock)
        for (i in 2 until rnsCommunitySequence.size) {
            val step = acc.step(rnsCommunitySequence[i], clock)
            assertTrue(
                "expected Accumulated for '${rnsCommunitySequence[i]}' but got $step",
                step is RoomListAccumulator.Step.Accumulated,
            )
        }
    }

    @Test
    fun `accumulator is inactive until a header starts a list`() {
        val acc = makeAccumulator()
        assertEquals(
            RoomListAccumulator.Step.NotActive,
            acc.step(rnsCommunitySequence[0], 0L),
        )
        // A header fed through step() is not a room line either — production
        // routes it to start() instead.
        assertEquals(RoomListAccumulator.Step.NotActive, acc.step(rnsCommunitySequence[1], 0L))
        assertFalse(acc.isActive)
    }

    @Test
    fun `replays the captured rnscommunity per-room sequence`() {
        val acc = makeAccumulator()
        val clock = 0L
        feedRnsRooms(acc, clock)
        assertTrue(acc.isActive)
        // Close with a foreign notice to read back the final list.
        val closed = acc.step("topic for lobby is now: test", clock) as RoomListAccumulator.Step.Closed
        assertEquals(expectedRnsRooms, closed.rooms)
        assertEquals("Registered public rooms:", closed.headerBody)
        assertTrue(closed.bodyConsumed)
    }

    @Test
    fun `list closes on the first non-room notice which is still processed`() {
        val acc = makeAccumulator()
        val clock = 0L
        feedRnsRooms(acc, clock)
        val step = acc.step("members in lobby: alice (ab12cd34)", clock)
        val closed = step as RoomListAccumulator.Step.Closed
        assertEquals(expectedRnsRooms, closed.rooms)
        assertTrue(closed.bodyConsumed)
        assertFalse(acc.isActive)
        // A later room line is no longer a list member.
        assertEquals(RoomListAccumulator.Step.NotActive, acc.step("  general", clock))
    }

    @Test
    fun `list closes on timeout and the stale line is dropped`() {
        val acc = makeAccumulator()
        feedRnsRooms(acc, 0L)
        val staleClock = RrcListParse.LINE_TIMEOUT_MS + 1
        val step = acc.step("  another-room", staleClock)
        val closed = step as RoomListAccumulator.Step.Closed
        assertEquals(expectedRnsRooms, closed.rooms)
        assertFalse("stale room line must not be reprocessed", closed.bodyConsumed)
        assertFalse(acc.isActive)
    }

    @Test
    fun `clear aborts an in-flight list`() {
        val acc = makeAccumulator()
        feedRnsRooms(acc, 0L)
        acc.clear()
        assertFalse(acc.isActive)
        assertEquals(RoomListAccumulator.Step.NotActive, acc.step("  general", 0L))
    }

    @Test
    fun `legacy header switches line parsing to nomadnet mode`() {
        val acc = makeAccumulator()
        acc.start("Rooms:", 0L)
        val step = acc.step("  lobby (2 members) - General chat", 0L)
        val accStep = step as RoomListAccumulator.Step.Accumulated
        assertEquals(listOf(room("lobby", "General chat")), accStep.rooms)
    }

    @Test
    fun `modern mode preserves parenthetical text in names`() {
        val acc = makeAccumulator()
        acc.start("Registered public rooms:", 0L)
        val step = acc.step("  music (live) - jam session", 0L)
        val accStep = step as RoomListAccumulator.Step.Accumulated
        assertEquals(listOf(room("music (live)", "jam session")), accStep.rooms)
    }

    // ------------------------- full wire replay through the production feed()

    @Test
    fun `replays the captured rnscommunity per-room wire sequence through feed`() {
        // The exact 12 roomless NOTICEs the rnscommunity hub sent (2026-09-11,
        // verbatim from the raw NOTICE log), in wire order, each at the clock
        // tick it arrived. The last notice (rrcbot) ends the list because no
        // further room line follows.
        val acc = makeAccumulator()
        var result = acc.feed(rnsCommunitySequence[0], 0L)
        assertEquals(
            "WELCOME greeting must not be treated as a room list",
            RoomListAccumulator.FeedResult.Unrelated,
            result,
        )

        result = acc.feed(rnsCommunitySequence[1], 1L)
        assertEquals(
            "header-only notice must start accumulation, not publish",
            RoomListAccumulator.FeedResult.Consumed,
            result,
        )

        for (i in 2 until rnsCommunitySequence.size) {
            val body = rnsCommunitySequence[i]
            val updated = acc.feed(body, (i + 1).toLong()) as RoomListAccumulator.FeedResult.Updated
            // After room i (1-based in the room sequence) the published list
            // must contain exactly the first i rooms, in wire order.
            assertEquals("partial list wrong after '$body'", expectedRnsRooms.take(i - 1), updated.rooms)
        }

        // The list is still open after the last captured room — in production
        // it closes when the next non-room notice arrives (or on timeout).
        assertTrue(acc.isActive)
        val closedNotice = "topic for general is now: RNS Community"
        val complete = acc.feed(closedNotice, (rnsCommunitySequence.size + 1).toLong()) as RoomListAccumulator.FeedResult.ListComplete
        assertEquals(expectedRnsRooms, complete.rooms)
        assertEquals("Registered public rooms:", complete.displayBody)
        assertTrue("foreign closer must be reprocessed by the caller", complete.passThroughBody)
        assertEquals(
            "all 10 rooms of the captured list must come through",
            listOf(
                "catfacts", "chat-hispano", "loelinverse", "nordisk", "retibooks",
                "general", "n2ycr", "linux", "reticulumberlin", "rrcbot",
            ),
            complete.rooms.map { it.name },
        )
    }

    @Test
    fun `replays the captured Colorado Mesh single-notice list through feed`() {
        // The exact single multi-line NOTICE captured from the Colorado Mesh
        // hub (2026-09-11): the whole list arrives in one body.
        val acc = makeAccumulator()
        val body =
            "Registered public rooms:\n" +
                "  general - https://coloradomesh.org/ - Everyone is welcome!\n" +
                "  mesh-client - Mesh Client Discussion https://github.com/Colorado-Mesh/mesh-client\n" +
                "  ratspeak - Discussions on Ratspeak https://github.com/ratspeak"
        val complete = acc.feed(body, 0L) as RoomListAccumulator.FeedResult.ListComplete
        assertEquals(
            listOf(
                room("general", "https://coloradomesh.org/ - Everyone is welcome!"),
                room("mesh-client", "Mesh Client Discussion https://github.com/Colorado-Mesh/mesh-client"),
                room("ratspeak", "Discussions on Ratspeak https://github.com/ratspeak"),
            ),
            complete.rooms,
        )
        assertEquals(body, complete.displayBody)
        assertFalse(complete.passThroughBody)
        assertFalse("no accumulation may remain after a multi-line list", acc.isActive)
    }

    @Test
    fun `list closed by a foreign notice passes the notice through`() {
        val acc = makeAccumulator()
        acc.feed("Registered public rooms:", 0L)
        val updated = acc.feed("  general", 1L) as RoomListAccumulator.FeedResult.Updated
        assertEquals(listOf(room("general")), updated.rooms)
        // A "/who" reply that ends the list is NOT list text — the caller
        // must still process it as an ordinary roomless notice.
        val closed = acc.feed("members in general: alice (ab12cd34)", 2L) as RoomListAccumulator.FeedResult.ListComplete
        assertEquals(listOf(room("general")), closed.rooms)
        assertTrue("foreign closer must be reprocessed by the caller", closed.passThroughBody)
    }

    @Test
    fun `oversized stream is aborted at the room cap`() {
        // A hostile/malfunctioning hub streaming valid room lines forever
        // must not grow the list without bound: at MAX_ROOMS the list is
        // aborted, the published rooms are capped, and the list closes.
        val acc = makeAccumulator()
        acc.feed("Registered public rooms:", 0L)
        val cap = RrcListParse.MAX_ROOMS
        for (i in 1 until cap) {
            val updated = acc.feed("  room$i - topic", i.toLong()) as RoomListAccumulator.FeedResult.Updated
            assertEquals(i, updated.rooms.size)
        }
        // Line cap+1 (room$cap) fills the list exactly.
        val full = acc.feed("  room$cap - topic", cap.toLong()) as RoomListAccumulator.FeedResult.Updated
        assertEquals(cap, full.rooms.size)
        // One more line: the cap is hit — abort, publish so far, drop it.
        val aborted = acc.feed("  room${cap + 1} - topic", (cap + 1).toLong()) as RoomListAccumulator.FeedResult.ListComplete
        assertEquals(cap, aborted.rooms.size)
        assertFalse("aborted line must not be reprocessed as a notice", aborted.passThroughBody)
        assertFalse(acc.isActive)
        // The accumulator is fully closed; further room lines are unrelated.
        assertEquals(RoomListAccumulator.FeedResult.Unrelated, acc.feed("  room${cap + 2} - topic", (cap + 2).toLong()))
    }
}
