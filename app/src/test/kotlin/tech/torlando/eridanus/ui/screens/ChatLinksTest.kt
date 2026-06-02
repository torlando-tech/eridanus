// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins down link detection in message bodies: bare NomadNet page addresses
 * (`<32-hex>:/path.mu`, optionally with a backtick field tail) are linkified
 * whole and kept distinct from http(s) URLs, while bare hashes and trailing
 * punctuation are deliberately left out.
 */
class ChatLinksTest {
    private val hash = "9ce92808be498e9e05590ff27cbfdfe4"

    /** Matched substrings, in order. */
    private fun texts(text: String): List<String> =
        detectChatLinks(text).map { it.text }

    /** Matched (kind, substring) pairs, in order. */
    private fun kinds(text: String): List<Pair<ChatLinkKind, String>> =
        detectChatLinks(text).map { it.kind to it.text }

    @Test
    fun bareNomadNetAddressIsLinkifiedWhole() {
        val text = "$hash:/page/forum/register.mu"
        assertEquals(listOf(ChatLinkKind.NOMADNET to text), kinds(text))
    }

    @Test
    fun nomadNetAddressInsideSurroundingTextIsLinkifiedWhole() {
        val addr = "$hash:/page/index.mu"
        assertEquals(listOf(addr), texts("Verify at $addr to continue"))
    }

    @Test
    fun backtickFieldTailIsCapturedWithTheAddress() {
        // The on-wire NomadNet link form: no scheme, hash:/path, then a
        // backtick-delimited field/query tail with `=` and `|` separators.
        val addr = "$hash:/page/index.mu`field1=value|field2=value"
        assertEquals(listOf(ChatLinkKind.NOMADNET to addr), kinds("open $addr now"))
    }

    @Test
    fun trailingClosingParenIsExcluded() {
        val addr = "$hash:/page/forum/register.mu"
        assertEquals(listOf(addr), texts("(see $addr)"))
    }

    @Test
    fun trailingSentencePunctuationIsExcluded() {
        val addr = "$hash:/page/index.mu"
        assertEquals(listOf(addr), texts("go to $addr."))
    }

    @Test
    fun bare32HexHashWithoutPathIsNotLinkified() {
        // Commonly a pasted identity/destination hash — not a page link.
        assertEquals(emptyList<String>(), texts("my address is $hash ok"))
    }

    @Test
    fun hexRunLongerThan32IsNotMatched() {
        // The leading lookbehind keeps a 32-char window from matching inside a
        // longer hex run, so a 40-hex blob with a path is not a NomadNet link.
        val long = hash + "0123456789abcdef0123"
        assertEquals(emptyList<String>(), texts("$long:/page/index.mu"))
    }

    @Test
    fun webUrlAndNomadNetAddressCoexistAsTwoSpans() {
        val addr = "$hash:/page/index.mu"
        assertEquals(
            listOf(
                ChatLinkKind.WEB to "https://example.com",
                ChatLinkKind.NOMADNET to addr,
            ),
            kinds("web https://example.com and node $addr"),
        )
    }

    @Test
    fun httpUrlContainingAHashStaysOneWebLink() {
        // A real http(s) URL wins by starting first; it isn't split into a
        // separate NomadNet span even though its tail looks address-shaped.
        val url = "https://example.com/$hash:/page/index.mu"
        assertEquals(listOf(ChatLinkKind.WEB to url), kinds(url))
    }

    @Test
    fun toNomadNetUriPrependsScheme() {
        val addr = "$hash:/page/index.mu`a=b"
        assertEquals("nomadnetwork://$addr", toNomadNetUri(addr))
    }
}
