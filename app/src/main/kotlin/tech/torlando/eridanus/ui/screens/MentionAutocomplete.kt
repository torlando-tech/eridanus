// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

import tech.torlando.eridanus.viewmodel.RoomMember

/**
 * A pending `@`-mention being typed in the composer: the index of the `@`
 * within the text ([start]) and the query typed after it ([partial], which is
 * empty immediately after the `@`). Pure data so the detection logic can be
 * unit-tested without Compose.
 */
internal data class MentionQuery(val start: Int, val partial: String)

/**
 * Detects an `@`-mention token under the cursor.
 *
 * Returns a [MentionQuery] when the whitespace-delimited word ending at
 * [cursor] begins with `@`; otherwise null. Because we only fire when the word
 * *starts* with `@`, email-style text like `foo@bar.com` is intentionally
 * ignored — its word starts with `f`, not `@`.
 *
 * [partial] never contains whitespace (the word stops at the previous
 * whitespace), so multi-word nicks like "John Doe" are matched by their first
 * word; the full nick — spaces and all — is spliced in on selection.
 */
internal fun mentionQueryAt(text: String, cursor: Int): MentionQuery? {
    if (cursor < 0 || cursor > text.length) return null
    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    // start == cursor means the word is empty (cursor at text start or right
    // after whitespace) — nothing to complete.
    if (start >= cursor) return null
    if (text[start] != '@') return null
    return MentionQuery(start, text.substring(start + 1, cursor))
}

/**
 * Members whose nick or hash prefix starts with [partial] (case-insensitive).
 * An empty [partial] matches everyone, so the full roster shows the moment a
 * `@` (or a bare command arg) is typed. Shared by the `@`-mention completer and
 * the command-arg completers (`/kick`, `/ban add`, …).
 */
internal fun List<RoomMember>.matchingMention(partial: String): List<RoomMember> {
    val p = partial.lowercase()
    return filter {
        p.isEmpty() ||
            it.nick?.lowercase()?.startsWith(p) == true ||
            it.hashPrefix.lowercase().startsWith(p)
    }
}
