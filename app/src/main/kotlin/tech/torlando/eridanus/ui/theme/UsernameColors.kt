// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Deterministically maps a user identity to a distinct, readable username color so
 * each speaker in a room stands out instead of everyone sharing the primary color.
 *
 * Only the *hue* is derived from [identity]; brightness is fixed (bright on dark
 * themes, deep on light themes). That keeps a given user's color stable — same
 * color across sessions and rooms — while every generated color stays legible on
 * the current background.
 *
 * Pass a stable identity key (the sender's destination-hash hex), NOT the display
 * nick: a user then keeps their color when they change nick, and two users who
 * happen to share a nick still get different colors.
 */
@Composable
fun usernameColor(identity: String): Color {
    // Chat text sits directly on the Scaffold's `background`; gauge dark vs light
    // from it so OLED black and the light themes are both handled correctly.
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(identity, darkTheme) { usernameColor(identity, darkTheme) }
}

private const val USERNAME_SATURATION = 0.80f

// Target *relative luminance* (not HSL lightness) for the username text. WCAG
// contrast is defined on luminance, and equal HSL lightness across hues yields
// wildly different luminance (yellow ≫ blue), so a fixed-lightness palette fails
// contrast for some hues. Fixing luminance instead gives every hue uniform
// contrast and uniform perceived brightness.
//   • Dark: 0.46 → bright pastels (≈ the old primary nick color), ≥ 5:1 even on
//     the elevated dark surfaces (Card / bottom sheet), ≥ 9:1 on the background.
//   • Light: 0.13 → deep saturated tones, ≥ 4.5:1 (WCAG AA) even on the darkest
//     light surface the names land on — the filled Card / bottom-sheet container,
//     which sit a tonal step below the near-white background.
private const val USERNAME_LUMINANCE_DARK = 0.46f
private const val USERNAME_LUMINANCE_LIGHT = 0.13f

/**
 * Theme-agnostic core of [usernameColor], split out so it can be unit-tested and
 * used in `@Preview`s without a live [MaterialTheme].
 */
fun usernameColor(identity: String, darkTheme: Boolean): Color {
    // FNV-1a (32-bit): a cheap, well-distributed hash so adjacent identities (and
    // short nick fallbacks) spread evenly around the hue circle. Int overflow wraps,
    // which is exactly the modular arithmetic the algorithm expects.
    var hash = 0x811C9DC5.toInt() // FNV offset basis
    for (ch in identity) {
        hash = hash xor ch.code
        hash *= 0x01000193 // FNV prime
    }
    val hue = (((hash % 360) + 360) % 360).toFloat() // floored modulo: hash can be negative

    // Solve HSL lightness for the target luminance by bisection. Luminance is
    // monotonic in lightness (at fixed hue/saturation), so this converges; ~18
    // steps pin lightness to <1/250000, far finer than 8-bit color.
    val target = if (darkTheme) USERNAME_LUMINANCE_DARK else USERNAME_LUMINANCE_LIGHT
    var lo = 0f
    var hi = 1f
    repeat(18) {
        val mid = (lo + hi) / 2f
        if (Color.hsl(hue, USERNAME_SATURATION, mid).luminance() < target) lo = mid else hi = mid
    }
    return Color.hsl(hue, USERNAME_SATURATION, (lo + hi) / 2f)
}
