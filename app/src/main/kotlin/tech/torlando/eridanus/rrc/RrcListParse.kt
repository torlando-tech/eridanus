package tech.torlando.eridanus.rrc

import tech.torlando.eridanus.viewmodel.AvailableRoom

/**
 * Parses rrcd `/list` room-list notices into [AvailableRoom] entries.
 *
 * Room-list notices are a hub TEXT convention, not a core RRC wire type, so
 * different rrcd versions serialize them differently. Two shapes have been
 * observed in the wild:
 *
 * 1. Single multi-line NOTICE: header plus one indented line per room,
 *    e.g. "Registered public rooms:\n  lobby - General chat\n  linux"
 *    (rrcd 0.3.x with a small room count; Eridanus's own hub).
 *
 * 2. One NOTICE per room: the header arrives alone and each room follows as
 *    its own indented NOTICE. Observed from the public rnscommunity hub
 *    (28c7c1a6...) on 2026-09-10 — presumably a larger room count or a
 *    newer rrcd. See handleRoomListNotice for the stateful accumulation.
 *
 * Each room line is "  name" or "  name - topic"; the topic may itself
 * contain " - " (e.g. loelinverse - - Etelectic Experiences...), so the
 * separator splits on the first " - " only. A topic of "(none)" is dropped.
 */
object RrcListParse {

    const val LIST_HEADER = "Registered public rooms:"
    /** Legacy rrc-nomadnet Python hub header (pre-Ara). */
    const val LEGACY_HEADER = "Rooms:"
    const val NO_ROOMS = "No public rooms registered"

    /** Per-line staleness window for the per-room NOTICE shape (seconds). */
    const val LINE_TIMEOUT_MS = 15_000L

    private val MEMBER_COUNT = Regex("""\s*\(\d+ members?\)""")

    /**
     * True when [body] is the room-list header (either convention), either
     * standalone (per-room NOTICE shape) or as the first line of a
     * multi-line notice.
     */
    fun isListHeader(body: String): Boolean {
        val first = body.trimStart().lineSequence().firstOrNull()?.trim() ?: return false
        return first == LIST_HEADER || first == LEGACY_HEADER
    }

    /** True when [body] is the hub's "no rooms" reply to `/list`. */
    fun isNoRooms(body: String): Boolean {
        val t = body.trim()
        return t == NO_ROOMS || t.equals("no rooms", ignoreCase = true)
    }

    /**
     * Parses one room-list line: "  name" or "  name - topic". Leading
     * indentation is REQUIRED — the hub emits every room line indented, and
     * requiring it keeps stray roomless notices (a WELCOME greeting, a
     * moderation notice, ...) from being swallowed as room names while the
     * per-room list is accumulating. Returns null for lines that are not
     * room entries (blank, unindented, or an unrecognized shape).
     *
     * [legacyNomadnet] strips "(N members)" from the name: the legacy
     * rrc-nomadnet hub embedded the member count there
     * ("lobby (2 members) - General chat"). Only applied to that convention
     * so a real topic containing "(N members)" elsewhere is preserved.
     */
    fun parseRoomLine(line: String, legacyNomadnet: Boolean = false): AvailableRoom? {
        if (line.isEmpty() || line[0] != ' ') return null
        val raw = line.trim()
        if (raw.isEmpty()) return null
        // "  - topic" is a hub entry with an EMPTY room name — reject, it
        // would otherwise become a room literally named "- topic".
        if (raw.startsWith("-")) return null
        val trimmed = if (legacyNomadnet) raw.replace(MEMBER_COUNT, "") else raw
        val idx = trimmed.indexOf(" - ")
        if (idx < 0) return AvailableRoom(name = trimmed, topic = null)
        val name = trimmed.substring(0, idx).trim()
        if (name.isEmpty()) return null
        val topic = trimmed.substring(idx + 3).trim()
            .takeIf { it.isNotEmpty() && !it.equals("(none)", ignoreCase = true) }
        return AvailableRoom(name = name, topic = topic)
    }

    /**
     * Parses a multi-line (shape 1) room-list notice into all of its rooms.
     * Returns null when [body] is not a multi-line list notice (header not
     * present or header line is not followed by room lines) — callers should
     * then treat the body as a plain notice.
     */
    fun parseListNotice(body: String): List<AvailableRoom>? {
        val lines = body.lines()
        val headerIdx = lines.indexOfFirst {
            it.trim() == LIST_HEADER || it.trim() == LEGACY_HEADER
        }
        if (headerIdx < 0) return null
        val legacy = lines[headerIdx].trim() == LEGACY_HEADER
        val rooms = lines.drop(headerIdx + 1)
            .mapNotNull { parseRoomLine(it, legacy) }
        if (rooms.isEmpty()) return null
        return rooms
    }
}
