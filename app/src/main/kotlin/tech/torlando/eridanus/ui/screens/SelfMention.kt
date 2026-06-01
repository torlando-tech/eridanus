// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

/**
 * Ranges within [text] where the local user's own display name [nick] is
 * mentioned as `@nick`, for highlighting in the chat view. Each range spans the
 * `@` through the end of the matched name.
 *
 * Matching is:
 * - **case-insensitive** — `@Bob` highlights for nick "bob";
 * - **word-boundary guarded** — `@bob` does not match inside `@bobby`, and the
 *   `@` must not sit mid-word, so an email like `foo@bob` is not a mention;
 * - **literal** — regex metacharacters in the nick (`.`, `+`, …) are escaped,
 *   and multi-word nicks ("John Doe") match in full.
 *
 * Returns empty when [nick] is blank.
 */
internal fun selfMentionRanges(text: String, nick: String): List<IntRange> {
    val trimmed = nick.trim()
    if (trimmed.isEmpty()) return emptyList()
    // (?U)       — UNICODE_CHARACTER_CLASS: makes \w cover non-ASCII word chars
    //              too (it's ASCII-only by default), so the guards below also
    //              reject accented neighbours — e.g. "@bobé" for nick "bob", or
    //              "ý@bob". Kotlin's RegexOption has no equivalent, so it's set
    //              as an embedded flag passed through to java.util.regex.
    // (?<![\w@]) — '@' isn't preceded by a word char (email) or another '@'.
    // (?!\w)     — the name isn't immediately followed by more word chars.
    val pattern = Regex(
        "(?U)(?<![\\w@])@" + Regex.escape(trimmed) + "(?!\\w)",
        RegexOption.IGNORE_CASE,
    )
    return pattern.findAll(text).map { it.range }.toList()
}
