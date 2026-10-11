package com.music.bitchord.ui

import com.music.bitchord.ui.screens.formatPlaybackSpeed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The speed row's readout: one decimal, trailing zeros trimmed, always with
 * the times mark. The values are the ones the slider's 0.05 steps actually
 * produce.
 */
class PlaybackSpeedFormatTest {

    @Test
    fun `whole speeds read without a decimal`() {
        assertEquals("1×", formatPlaybackSpeed(1.0f))
        assertEquals("2×", formatPlaybackSpeed(2.0f))
        assertEquals("0.5×", formatPlaybackSpeed(0.5f))
    }

    @Test
    fun `fractional speeds keep their digits and lose the padding`() {
        assertEquals("1.25×", formatPlaybackSpeed(1.25f))
        assertEquals("1.5×", formatPlaybackSpeed(1.50f))
        assertEquals("0.75×", formatPlaybackSpeed(0.75f))
    }
}
