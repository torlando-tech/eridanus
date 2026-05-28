// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.theme

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the two non-obvious guarantees of [usernameColor]: every speaker gets a
 * distinct, *deterministic* color, and each color hits the target relative
 * luminance that keeps it WCAG-AA legible on its theme. A naive "fixed HSL
 * lightness" implementation would pass determinism/distinctness but silently fail
 * the luminance check (yellow ≫ blue at equal lightness), so that assertion is the
 * real regression guard.
 */
class UsernameColorsTest {

    // Realistic destination-hash prefixes plus a few nick fallbacks.
    private val identities = buildList {
        repeat(64) { add("%012x".format(it * 0x9E3779B97F4A7C15uL.toLong())) }
        addAll(listOf("alice", "bob", "carol", "dave", "erin", "frank"))
    }

    @Test
    fun colorIsDeterministicPerIdentityAndTheme() {
        for (id in identities) {
            assertEquals(usernameColor(id, darkTheme = true), usernameColor(id, darkTheme = true))
            assertEquals(usernameColor(id, darkTheme = false), usernameColor(id, darkTheme = false))
        }
    }

    @Test
    fun distinctIdentitiesGetMostlyDistinctColors() {
        val colors = identities.map { usernameColor(it, darkTheme = true) }.toSet()
        // Hue is the only varying axis (luminance/saturation are fixed), so identities
        // are pigeonholed into 360 slots: a handful of collisions among ~70 identities
        // is expected (birthday paradox). The guard is against a *clustering* hash,
        // which would collapse these to far fewer distinct colors.
        assertTrue(
            "hash distributes poorly: only ${colors.size} of ${identities.size} distinct",
            colors.size >= identities.size * 4 / 5,
        )
    }

    @Test
    fun everyColorHitsTargetLuminanceForContrast() {
        // The targets the implementation solves for. Dark ≈ old bright-pastel primary
        // (≥5:1 even on elevated dark surfaces); light is deep enough for ≥4.5:1 even
        // on the darkest light surface the names land on (filled Card container).
        for (id in identities) {
            assertEquals(0.46f, usernameColor(id, darkTheme = true).luminance(), 0.03f)
            assertEquals(0.13f, usernameColor(id, darkTheme = false).luminance(), 0.03f)
        }
    }
}
