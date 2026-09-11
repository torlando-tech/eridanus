package tech.torlando.eridanus.rrc

import java.util.concurrent.locks.ReentrantLock
import tech.torlando.eridanus.viewmodel.AvailableRoom

/**
 * Stateful accumulator for the per-room `/list` notice shape: the hub sends
 * the "Registered public rooms:" header as one NOTICE and then each room as
 * its own indented NOTICE (observed from the rnscommunity hub, 2026-09).
 *
 * Production entry point: [feed] — it routes one roomless NOTICE through
 * the whole room-list decision (multi-line parse, header start, line
 * accumulation, close) and reports what the caller should publish or
 * display, so the exact ViewModel routing is unit-testable against captured
 * hub traffic. The lower-level [start]/[step] remain for direct testing of
 * the accumulation state.
 *
 * The list closes when a non-room notice arrives (reported as
 * [FeedResult.ListComplete] with `passThroughBody=true` so the caller can
 * still process it normally) or when no line arrives within [timeoutMillis].
 *
 * All state mutations are guarded by a lock so the accumulator is safe
 * whichever thread the event collector (and `clear()` from disconnect
 * paths) runs on.
 *
 * Pure Kotlin (no Android) so the timeout and close semantics are unit-testable.
 */
class RoomListAccumulator(
    private val timeoutMillis: Long = RrcListParse.LINE_TIMEOUT_MS,
) {
    /** Outcome of feeding one roomless notice into the accumulator. */
    sealed class Step {
        /** [body] was a room line; the notice is consumed by the list. */
        data class Accumulated(val rooms: List<AvailableRoom>) : Step()
        /**
         * The list ended on this notice. [rooms] is the final accumulated
         * list, [headerBody] is the stored header (for display), and
         * [bodyConsumed] says whether the caller should process [body] as a
         * plain notice (true — it was the list terminator) or drop it
         * (false — it was a stale room line past the timeout).
         */
        data class Closed(
            val rooms: List<AvailableRoom>,
            val headerBody: String?,
            val bodyConsumed: Boolean,
        ) : Step()
        /** No list was in flight; the notice is unrelated to a `/list`. */
        data object NotActive : Step()
    }

    /**
     * Outcome of feeding one roomless hub NOTICE through [feed] — the
     * complete room-list decision the ViewModel used to inline.
     */
    sealed class FeedResult {
        /**
         * The room list is complete: either a single multi-line list notice
         * or the accumulator just closed. Publish [rooms] as the available
         * room list and display [displayBody] as the hub notice.
         * [passThroughBody] is true when the closing notice is not itself a
         * list artifact and the caller should still process it as an
         * ordinary roomless notice (e.g. a "/who" reply that ended the
         * list); false when it was the list body or a stale room line.
         */
        data class ListComplete(
            val rooms: List<AvailableRoom>,
            val displayBody: String,
            val passThroughBody: Boolean,
        ) : FeedResult()

        /** A room line arrived; publish [rooms]. The notice is consumed. */
        data class Updated(val rooms: List<AvailableRoom>) : FeedResult()

        /**
         * The notice was consumed by list handling (a header-only notice
         * that started accumulation). Nothing to publish or display yet.
         */
        data object Consumed : FeedResult()

        /** No list is in flight; the notice is unrelated to a `/list`. */
        data object Unrelated : FeedResult()
    }

    private val lock = ReentrantLock()
    private var active = false
    private val rooms = mutableListOf<AvailableRoom>()
    private var legacy = false
    private var headerBody: String? = null
    private var lastLineAtMillis: Long = 0L

    val isActive: Boolean
        get() {
            lock.lock()
            try {
                return active
            } finally {
                lock.unlock()
            }
        }

    /**
     * Production entry point: routes one roomless hub NOTICE through the
     * complete room-list decision (the logic the ViewModel used to inline in
     * its NoticeReceived handler). The caller publishes [FeedResult.ListComplete.rooms]
     * / [FeedResult.Updated.rooms] to its available-rooms state and displays
     * the notice when instructed; if [FeedResult.ListComplete.passThroughBody]
     * is true the body must additionally be processed as an ordinary
     * roomless notice (it ended the list but belongs to some other hub
     * reply, e.g. "/who").
     */
    fun feed(body: String, nowElapsedMillis: Long): FeedResult {
        if (RrcListParse.isListHeader(body)) {
            val parsed = RrcListParse.parseListNotice(body)
            return if (parsed != null) {
                FeedResult.ListComplete(
                    rooms = parsed,
                    displayBody = body,
                    passThroughBody = false,
                )
            } else {
                start(body, nowElapsedMillis)
                FeedResult.Consumed
            }
        }
        return when (val step = step(body, nowElapsedMillis)) {
            is Step.Accumulated -> FeedResult.Updated(step.rooms)
            is Step.Closed -> FeedResult.ListComplete(
                rooms = step.rooms,
                displayBody = step.headerBody ?: RrcListParse.LIST_HEADER,
                passThroughBody = step.bodyConsumed,
            )
            is Step.NotActive -> FeedResult.Unrelated
        }
    }

    /** Starts accumulating a new list from [headerBody]. */
    fun start(headerBody: String, nowElapsedMillis: Long) {
        lock.lock()
        try {
            active = true
            rooms.clear()
            this.headerBody = headerBody.trim()
            legacy =
                headerBody.trimStart().lineSequence().firstOrNull()?.trim() == RrcListParse.LEGACY_HEADER
            lastLineAtMillis = nowElapsedMillis
        } finally {
            lock.unlock()
        }
    }

    /**
     * Feeds one roomless notice body. Returns [Step.NotActive] when no list
     * is in flight (body is untouched), [Step.Accumulated] when it was a
     * room line, or [Step.Closed] when it ended the list.
     */
    fun step(body: String, nowElapsedMillis: Long): Step {
        lock.lock()
        try {
            if (!active) return Step.NotActive
            val stale = nowElapsedMillis - lastLineAtMillis > timeoutMillis
            val room = RrcListParse.parseRoomLine(body, legacy)
            if (!stale && room != null) {
                if (rooms.size >= RrcListParse.MAX_ROOMS) {
                    // Hostile or malfunctioning hub streaming room lines
                    // forever: abort, publish what we have, drop the line.
                    val finalRooms = rooms.toList()
                    val storedHeader = headerBody
                    active = false
                    rooms.clear()
                    legacy = false
                    headerBody = null
                    return Step.Closed(finalRooms, storedHeader, bodyConsumed = false)
                }
                rooms.add(room)
                lastLineAtMillis = nowElapsedMillis
                return Step.Accumulated(rooms.toList())
            }
            val finalRooms = rooms.toList()
            val storedHeader = headerBody
            active = false
            rooms.clear()
            legacy = false
            headerBody = null
            return Step.Closed(finalRooms, storedHeader, bodyConsumed = room == null)
        } finally {
            lock.unlock()
        }
    }

    /** Aborts an in-flight list without processing any notice. */
    fun clear() {
        lock.lock()
        try {
            active = false
            rooms.clear()
            legacy = false
            headerBody = null
        } finally {
            lock.unlock()
        }
    }
}
