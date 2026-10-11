package com.music.bitchord.desktop.playback

import com.music.bitchord.playback.EqCurve
import com.music.bitchord.playback.EqLayout
import com.music.bitchord.playback.EqualizerPreset
import com.music.bitchord.playback.manualCurve
import com.music.bitchord.playback.toneCurve
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The curve-model-to-libvlc mapping, pinned on the three shapes the design
 * names — FLAT all-zero, a tone extreme, a manual table — plus the clamp and
 * the preamp passthrough. The band centres are the mapper's own default, which
 * is libvlc 3.x's fixed table; no native factory is stood up for this.
 */
class EqualizerMapperTest {

    /** FLAT must map to all-zero amps and zero preamp — by construction, not special case. */
    @Test
    fun `a flat curve maps to all zeros`() {
        val tuning = equalizerTuning(manualCurve(EqualizerPreset.FLAT.bands), enabled = true)
        assertTrue(tuning.enabled)
        assertEquals(VLC_BAND_COUNT, tuning.bandAmps.size)
        for (amp in tuning.bandAmps) assertEquals(0f, amp)
        assertEquals(0f, tuning.preampDb)
        // The model's own FLAT constant goes one better: a curve nobody touched.
        val flat = equalizerTuning(EqCurve.FLAT, enabled = true)
        assertTrue(flat.bandAmps.all { it == 0f })
        assertEquals(0f, flat.preampDb)
    }

    /** Off is the passthrough: the engine detaches, whatever curve it was handed. */
    @Test
    fun `a disabled mapping is all zeros whatever the curve says`() {
        val loud = manualCurve(EqualizerPreset.BASS_BOOST.bands)
        val tuning = equalizerTuning(loud, enabled = false)
        assertEquals(EqualizerTuning.OFF, tuning)
        assertTrue(tuning.bandAmps.all { it == 0f })
        assertEquals(0f, tuning.preampDb)
    }

    /** The pad pushed hard right: bass down, treble up, the middle where it was. */
    @Test
    fun `a tone extreme keeps its shape at the vlc bands`() {
        val bright = equalizerTuning(toneCurve(x = EqLayout.TONE_STEPS, y = 0, focused = false), enabled = true)
        // 31.25 Hz sits under the 250 Hz low shelf's full cut; 16 kHz under the
        // 4 kHz high shelf's full lift. The corner is ±6 dB.
        assertTrue(bright.bandAmps.first() < -4f, "31.25 Hz was ${bright.bandAmps.first()}")
        assertTrue(bright.bandAmps.last() > 4f, "16 kHz was ${bright.bandAmps.last()}")
        // The middle of the spectrum keeps its level — the tilt's whole point.
        assertTrue(abs(bright.bandAmps[5]) < 0.5f, "1 kHz was ${bright.bandAmps[5]}")
        assertTrue(abs(bright.bandAmps[4]) < 1.5f, "500 Hz was ${bright.bandAmps[4]}")
        // And the other way round mirrors it.
        val warm = equalizerTuning(toneCurve(x = -EqLayout.TONE_STEPS, y = 0, focused = false), enabled = true)
        assertTrue(warm.bandAmps.first() > 4f)
        assertTrue(warm.bandAmps.last() < -4f)
    }

    /** The pad pushed hard up: the mid bell lifts, the ends stay put. */
    @Test
    fun `the contour extreme lifts the mids and leaves the ends alone`() {
        val forward = equalizerTuning(toneCurve(x = 0, y = EqLayout.TONE_STEPS, focused = true), enabled = true)
        assertTrue(forward.bandAmps[5] > 4f, "1 kHz was ${forward.bandAmps[5]}")
        assertTrue(abs(forward.bandAmps.first()) < 1f, "31.25 Hz was ${forward.bandAmps.first()}")
        assertTrue(abs(forward.bandAmps.last()) < 1f, "16 kHz was ${forward.bandAmps.last()}")
    }

    /**
     * A known manual table, one band up: the shelf's corner lands half way up
     * at 62.5 Hz and the full amount below it — the shape the DSP test
     * measured upstream, read straight off the sampled sum here.
     */
    @Test
    fun `a single boosted low shelf reads its corner at the vlc bands`() {
        val bands = List(EqLayout.MANUAL_COUNT) { 0f }
        val tuning = equalizerTuning(manualCurve(listOf(6f) + bands.drop(1)), enabled = true)
        assertTrue(tuning.bandAmps[0] > 5f, "31.25 Hz was ${tuning.bandAmps[0]} (shelf, below the corner)")
        // The corner reads its half-way amount at 60 Hz; 62.5 is already past
        // it, where the Q=1 shelf has begun to let go — the sampled truth of
        // the analytic section, not a broken mapping.
        assertTrue(
            tuning.bandAmps[1] in 2.2f..3.2f,
            "62.5 Hz was ${tuning.bandAmps[1]} (the shelf's corner skirt)",
        )
        assertTrue(abs(tuning.bandAmps[5]) < 0.3f, "1 kHz was ${tuning.bandAmps[5]}")
    }

    /** And a preset table renders every band inside libvlc's reach, shape intact. */
    @Test
    fun `every preset maps inside libvlc's range with its loudest band above its flat bands`() {
        for (preset in EqualizerPreset.entries) {
            if (preset == EqualizerPreset.CUSTOM) continue
            val tuning = equalizerTuning(manualCurve(preset.bands), enabled = true)
            assertEquals(VLC_BAND_COUNT, tuning.bandAmps.size, preset.name)
            assertTrue(
                tuning.bandAmps.all { abs(it) <= VLC_BAND_LIMIT_DB },
                "${preset.name} produced ${tuning.bandAmps.toList()}",
            )
            assertTrue(tuning.preampDb <= 0.001f, "${preset.name} preamp ${tuning.preampDb} should attenuate")
            // Every curve that actually moves something still moves something
            // after mapping — FLAT is the one preset that legitimately maps to
            // silence (pinned on its own above).
            if (preset != EqualizerPreset.FLAT) {
                assertTrue(tuning.bandAmps.any { abs(it) > 0.5f }, "${preset.name} mapped to silence")
            }
        }
    }

    /** The clamp is a safety net for hand-built curves, not a shaping step. */
    @Test
    fun `an absurd curve is clamped to libvlc's ceiling`() {
        // Built by hand rather than through the sliders, which top out at ±12.
        val absurd = EqCurve(
            FloatArray(EqLayout.SLOTS) { 40f },
            FloatArray(EqLayout.SLOTS) { 0.707f },
            0f,
        )
        val tuning = equalizerTuning(absurd, enabled = true)
        assertTrue(tuning.bandAmps.all { it <= VLC_BAND_LIMIT_DB })
        assertTrue(tuning.bandAmps.all { it >= -VLC_BAND_LIMIT_DB })
        // A preamp somebody built by hand above zero comes down too.
        val hot = EqCurve(FloatArray(EqLayout.SLOTS), FloatArray(EqLayout.SLOTS) { 0.707f }, 30f)
        assertEquals(VLC_BAND_LIMIT_DB, equalizerTuning(hot, true).preampDb)
    }

    /** The preamp rides through untouched when it is inside the range. */
    @Test
    fun `the preamp passes through as the curve computed it`() {
        val curve = manualCurve(EqualizerPreset.BASS_BOOST.bands)
        assertTrue(curve.preampDb < 0f)
        assertEquals(curve.preampDb, equalizerTuning(curve, enabled = true).preampDb)
    }

    /** The centres are libvlc 3.x's own table, low to high. */
    @Test
    fun `the band centres are libvlc's ten`() {
        assertEquals(listOf(31.25f, 62.5f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f), VLC_BAND_CENTERS_HZ)
        assertEquals(VLC_BAND_COUNT, VLC_BAND_CENTERS_HZ.size)
        // Ascending — the mapper writes amps low to high and the engine reads them back the same way.
        assertEquals(VLC_BAND_CENTERS_HZ.sorted(), VLC_BAND_CENTERS_HZ)
    }

    /** Tunings compare by content, FloatArray included. */
    @Test
    fun `tunings compare by value`() {
        val a = equalizerTuning(manualCurve(EqualizerPreset.ROCK.bands), enabled = true)
        val b = equalizerTuning(manualCurve(EqualizerPreset.ROCK.bands), enabled = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("enabled=true"))
    }

    // ── Loudness normalization's gain (slice 18) ─────────────────────────────

    /** Zero gain is the identity, on and off — nothing starts equalizing because a dial moved. */
    @Test
    fun `a zero loudness gain leaves the tuning exactly as it was`() {
        val off = EqualizerTuning.OFF
        assertSame(off, withLoudnessGain(off, 0f))
        val on = equalizerTuning(manualCurve(EqualizerPreset.ROCK.bands), enabled = true)
        assertSame(on, withLoudnessGain(on, 0f))
    }

    /** The one case the attachment exists for: no equalizer, a real correction. */
    @Test
    fun `a gain on a detached tuning attaches a flat equalizer with the preamp alone`() {
        val dressed = withLoudnessGain(EqualizerTuning.OFF, -12f)
        assertTrue(dressed.enabled, "the gain needs the equalizer attached to exist")
        assertTrue(dressed.bandAmps.all { it == 0f }, "flat: the gain is the only change")
        assertEquals(-12f, dressed.preampDb)
    }

    /** An enabled tuning takes the gain added to its own preamp, bands untouched. */
    @Test
    fun `a gain on an enabled tuning sums into the preamp`() {
        val base = equalizerTuning(manualCurve(EqualizerPreset.ROCK.bands), enabled = true)
        val dressed = withLoudnessGain(base, 2.5f)
        assertEquals(base.preampDb + 2.5f, dressed.preampDb)
        assertTrue(dressed.bandAmps.contentEquals(base.bandAmps))
        assertTrue(dressed.enabled)
    }

    /** libvlc's ±20 dB ceiling survives the sum: the clamp is a corner, not a shape. */
    @Test
    fun `the summed preamp is clamped to libvlc's ceiling`() {
        val base = equalizerTuning(manualCurve(EqualizerPreset.ROCK.bands), enabled = true)
        val dressed = withLoudnessGain(base, -20f)
        assertEquals(-VLC_BAND_LIMIT_DB, dressed.preampDb)
        // A hand-built hot curve gets pulled back down from the other end too.
        val hot = EqualizerTuning(enabled = true, preampDb = 19f, bandAmps = FloatArray(VLC_BAND_COUNT))
        assertEquals(VLC_BAND_LIMIT_DB, withLoudnessGain(hot, 3f).preampDb)
    }

    /** The boost window's top: a quiet track corrected by +3 dB rides through under the ceiling. */
    @Test
    fun `a boost within the window rides through`() {
        val dressed = withLoudnessGain(EqualizerTuning.OFF, 3f)
        assertEquals(3f, dressed.preampDb)
        assertTrue(dressed.bandAmps.all { it == 0f })
    }
}
