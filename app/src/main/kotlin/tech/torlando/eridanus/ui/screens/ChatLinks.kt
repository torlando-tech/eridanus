// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

/**
 * Which kind of link a detected span is, so the renderer can wire the right tap
 * behaviour: a [WEB] link opens in the system browser (via `LinkAnnotation.Url`
 * → `LocalUriHandler`), while a [NOMADNET] address is handed to an installed
 * NomadNet-capable app through an `ACTION_VIEW` intent.
 */
internal enum class ChatLinkKind { WEB, NOMADNET }

/**
 * A detected link span in a message body: the [range] (inclusive indices into
 * the source text), the matched [text], and its [kind].
 */
internal data class ChatLink(val range: IntRange, val text: String, val kind: ChatLinkKind)

/**
 * http(s) URLs. An explicit scheme is required so a bare domain — including the
 * `.mu` tail of a NomadNet page path — is not mistaken for a web link.
 */
internal val WEB_URL_REGEX = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)

/**
 * A bare NomadNet page address: a 32-hex destination hash, then `:/`, then the
 * page path — e.g. `9ce92808be498e9e05590ff27cbfdfe4:/page/index.mu`. There is
 * **no** `nomadnetwork://` scheme on the wire; the address is written bare. The
 * path run also captures any trailing micron field/query data appended after a
 * backtick (`` `field=value|other=value ``), since `` ` ``, `=` and `|` are all
 * permitted path characters.
 *
 * Deliberate boundaries:
 * - the leading `(?<![0-9a-fA-F])` stops a 32-char window from matching inside a
 *   longer hex run;
 * - a bare 32-hex hash with **no** `:/path` is not matched — those are commonly
 *   pasted identity/destination hashes, not page links;
 * - the path class excludes `)` `]` and the trailing `(?<![.,;:])` drops a
 *   sentence terminator the address bumps up against, so `(see …index.mu).` →
 *   `…index.mu`.
 *
 * Only fixed-length lookbehind is used and the character classes are spelled out
 * rather than relying on `\w`/`(?U)`, so this compiles and behaves identically
 * on Android's ICU regex engine and the desktop JVM (see [selfMentionRanges]
 * for the same Android-regex caveat).
 */
internal val NOMADNET_ADDRESS =
    Regex("""(?<![0-9a-fA-F])[0-9a-fA-F]{32}:/[^\s,;!?)\]]+(?<![.,;:])""")

/**
 * Detect all web and NomadNet link spans in [text], resolving overlaps so the
 * returned spans never collide.
 *
 * Resolution: earliest start wins; ties break toward the longer span. This
 * gives a bare NomadNet address precedence over a stray `http`-shaped fragment
 * nested inside its path, while a genuine `http://…` that merely *contains* a
 * hash still wins because it starts first.
 *
 * Returned spans are ordered by start index.
 */
internal fun detectChatLinks(text: String): List<ChatLink> {
    val candidates = ArrayList<ChatLink>()
    NOMADNET_ADDRESS.findAll(text).forEach {
        candidates += ChatLink(it.range, it.value, ChatLinkKind.NOMADNET)
    }
    WEB_URL_REGEX.findAll(text).forEach {
        candidates += ChatLink(it.range, it.value, ChatLinkKind.WEB)
    }
    if (candidates.size <= 1) return candidates
    // Earliest start first; on equal start the longer span first. Then greedily
    // keep a candidate only if it starts past the last one we kept.
    candidates.sortWith(compareBy({ it.range.first }, { -it.range.last }))
    val resolved = ArrayList<ChatLink>(candidates.size)
    var lastEnd = -1
    for (c in candidates) {
        if (c.range.first <= lastEnd) continue
        resolved += c
        lastEnd = c.range.last
    }
    return resolved
}

/**
 * The browsable URI for a bare NomadNet [address] (as matched by
 * [NOMADNET_ADDRESS]): the `nomadnetwork://` scheme is prepended so an
 * `ACTION_VIEW` intent resolves to an installed NomadNet-capable app. The
 * address — hash, path, and any backtick field tail — is passed through
 * verbatim; the receiving app does its own parsing.
 */
internal fun toNomadNetUri(address: String): String = "nomadnetwork://$address"
