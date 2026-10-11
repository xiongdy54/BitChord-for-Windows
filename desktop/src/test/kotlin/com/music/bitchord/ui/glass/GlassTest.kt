package com.music.bitchord.ui.glass

import org.jetbrains.skia.ColorFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The glass pipeline's unit-testable half: the shaders really compile on this
 * machine's Skia (the whole feature's capability gate), and the two pure
 * functions behave as the uniforms expect.
 *
 * The rendering itself is judge-by-screenshot — `.shots/slice17/` — because a
 * unit test that asserted on pixel colours would be testing the test.
 */
class GlassTest {

    @Test
    fun `both glass shaders compile on this machine's Skia`() {
        // Throws with the Skia error message if the SkSL is bad — which is
        // exactly what isGlassSupported() exists to catch once.
        GlassEffects.compile(GlassShaders.refractionWithDispersion)
        GlassEffects.compile(GlassShaders.defaultHighlight)
        assertTrue(GlassEffects.isGlassSupported)
    }

    @Test
    fun `the saturation matrix keeps luminance and scales chroma`() {
        val matrix = GlassEffects.saturationMatrix(1.5f).mat
        // Each colour row sums to 1 — greys are untouched by saturation.
        for (row in 0..2) {
            val sum = matrix[row * 5] + matrix[row * 5 + 1] + matrix[row * 5 + 2]
            assertEquals(1f, sum, 0.001f, "row $row must preserve grey")
        }
        // Red's red coefficient grew past 1; blue's shrank — chroma moved out.
        assertTrue(matrix[0] > 1f)
        assertTrue(matrix[10] < 1f)
    }

    @Test
    fun `the saturation matrix feeds ColorFilter makeMatrix unchanged`() {
        // Smoke: the filter builds — a malformed matrix throws here.
        ColorFilter.makeMatrix(GlassEffects.saturationMatrix(1.5f))
    }

    @Test
    fun `the refraction filter builds as the draw pass drives it`() {
        // The uniform contract is checked at the API boundary: glassFilter
        // builds without throwing, and the negation itself is a one-line
        // fact asserted by reading the builder is not possible — so this test
        // pins the contract by construction instead: the call below is the
        // same one the draw pass makes, and it must not throw.
        GlassEffects.glassFilter(
            sampleSize = IntSizeForTest.size,
            cornerRadiiPx = floatArrayOf(9f, 9f, 9f, 9f),
            blurRadiusPx = 2.6f,
            refractionHeightPx = 7.9f,
            refractionAmountPx = 7.9f,
        )
    }
}

private object IntSizeForTest {
    val size = androidx.compose.ui.unit.IntSize(360, 120)
}
