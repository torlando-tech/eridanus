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
    // A Unicode-aware "word char" class. We deliberately avoid `\w`: it's
    // ASCII-only on Android's (ICU-backed) regex engine, and the `(?U)` flag
    // that would widen it on the desktop JVM throws PatternSyntaxException on
    // Android — so JVM unit tests can't catch the difference. `[\p{L}\p{N}_]`
    // is Unicode-aware on *both* engines, so the boundary guards also reject
    // accented neighbours (e.g. "@bobé" for nick "bob", or "ý@bob").
    val word = "\\p{L}\\p{N}_"
    // (?<![word@]) — '@' isn't preceded by a word char (email) or another '@'.
    // (?![word])   — the name isn't immediately followed by more word chars.
    val pattern = Regex(
        "(?<![$word@])@" + Regex.escape(trimmed) + "(?![$word])",
        RegexOption.IGNORE_CASE,
    )
    return pattern.findAll(text).map { it.range }.toList()
}
