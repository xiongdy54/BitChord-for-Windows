package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The measurement's labels, as a truth table.
 *
 * Every derived property is decided on the measured figures alone — that rule
 * is the whole point of holding a decoder's verdict apart from what a source
 * promised, so the table pins the rule codec by codec and rate by rate.
 */
class NerdStatsSnapshotTest {

    private fun snapshot(
        mime: String?,
        kbps: Int? = null,
        rate: Int? = null,
    ) = NerdStats.Snapshot(
        mimeType = mime,
        bitrateKbps = kbps,
        sampleRateHz = rate,
        channels = 2,
    )

    @Test
    fun `a flac codec is lossless, whatever the bitrate would have said`() {
        assertTrue(snapshot("flac", kbps = 100).isLossless)
        assertTrue(snapshot("flac", kbps = 60).isLossless)
    }

    @Test
    fun `opus never reads as lossless, however good it sounds`() {
        assertFalse(snapshot("opus", kbps = 160).isLossless)
        assertFalse(snapshot("opus", kbps = 510).isLossless)
    }

    @Test
    fun `past 48kHz is Hi-Res, at it or under it is plain Lossless`() {
        assertTrue(snapshot("flac", rate = 96_000).isHiRes)
        assertTrue(snapshot("flac", rate = 44_100).isLossless)
        assertFalse(snapshot("flac", rate = 44_100).isHiRes)
    }

    @Test
    fun `youtube's opus tiers land where the listener expects them`() {
        assertTrue(snapshot("opus", kbps = 64).isLowQuality)
        assertTrue(snapshot("opus", kbps = 160).isMediumQuality)
        assertFalse(snapshot("opus", kbps = 160).isHiQuality)
        assertTrue(snapshot("mp4a", kbps = 256).isHiQuality)
    }

    @Test
    fun `eac3-joc is Dolby Atmos and deliberately not lossless`() {
        assertTrue(snapshot("eac3-joc", kbps = 384).isDolbyAtmos)
        assertFalse(snapshot("eac3-joc", kbps = 384).isLossless)
    }

    @Test
    fun `an absent figure is never evidence of a quality`() {
        val nothing = snapshot("opus", kbps = null)
        assertFalse(nothing.isLowQuality)
        assertFalse(nothing.isMediumQuality)
        assertFalse(nothing.isHiQuality)
    }

    @Test
    fun `the codec label is the word a listener recognises`() {
        assertEquals("FLAC", NerdStats.codecLabel("flac"))
        assertEquals("Opus", NerdStats.codecLabel("opus"))
        assertEquals("AAC", NerdStats.codecLabel("mp4a-latm"))
        assertEquals("PCM", NerdStats.codecLabel("araw"))
        assertEquals("WMAV2", NerdStats.codecLabel("wmav2"))
        assertNull(NerdStats.codecLabel(null))
    }

    @Test
    fun `a measurement lands only on the track it named`() {
        NerdStats.clear()
        try {
            NerdStats.onMeasured("a", snapshot("flac", kbps = 900))
            assertEquals("a", NerdStats.currentVideoId())
            assertTrue(NerdStats.current.value?.isLossless == true)
            // The needle moved, the report didn't: a late arrival is discarded.
            NerdStats.onMeasured(null, snapshot("opus", kbps = 160))
            assertNull(NerdStats.currentVideoId())
            assertEquals(160, NerdStats.current.value?.bitrateKbps)
            // And the track change itself sweeps the figures before the next
            // measurement could ever arrive.
            NerdStats.clear()
            assertNull(NerdStats.current.value)
        } finally {
            NerdStats.clear()
        }
    }
}
