package com.music.bitchord.playback

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The curve model, pinned on its own analytic truths — the same assertions
 * upstream's `EqualizerProcessorTest` makes by *measuring* a DSP, made here
 * against the maths itself, since the desktop's mapping layer is what consumes
 * these functions and the processor behind them never got ported.
 */
class EqualizerCurveTest {

    // ---- layout ------------------------------------------------------------

    @Test
    fun `the layout is ten slots, seven manual and three tone`() {
        assertEquals(10, EqLayout.SLOTS)
        assertEquals(EqLayout.slots.size, EqLayout.SLOTS)
        assertEquals(7, EqLayout.MANUAL_COUNT)
        // The tone slots sit after the manual ones, in low-mid-high order.
        assertEquals(listOf(250f, 1_000f, 4_000f), listOf(
            EqLayout.slots[EqLayout.TONE_LOW].frequencyHz,
            EqLayout.slots[EqLayout.TONE_MID].frequencyHz,
            EqLayout.slots[EqLayout.TONE_HIGH].frequencyHz,
        ))
        // The manual ends are shelves, the interior bells — the shape the
        // "turn the bass up" argument in [FilterKind]'s KDoc buys.
        assertEquals(FilterKind.LOW_SHELF, EqLayout.slots[0].kind)
        assertEquals(FilterKind.HIGH_SHELF, EqLayout.slots[6].kind)
        for (band in 1..5) assertEquals(FilterKind.BELL, EqLayout.slots[band].kind)
    }

    // ---- sections ------------------------------------------------------------

    /** A bell is exactly its own gain at its own centre — the definition of the curve. */
    @Test
    fun `a bell reads its own gain at its own centre`() {
        for (band in 1 until EqLayout.MANUAL_COUNT - 1) {
            val slot = EqLayout.slots[band]
            val gain = sectionGainDb(slot, 6f, EqLayout.MANUAL_Q, slot.frequencyHz)
            assertEquals(6.0, gain.toDouble(), 0.01, "band ${slot.frequencyHz} Hz")
        }
    }

    /**
     * The two end bands are shelves, not bells: half way up at their corner
     * and all the way past it, which is what makes "more bass" mean the bass
     * people mean (upstream's measured assertion, made analytic here).
     */
    @Test
    fun `a shelf is half way up at its corner and all the way below it`() {
        val low = EqLayout.slots[0] // 60 Hz low shelf
        assertEquals(3.0, sectionGainDb(low, 6f, EqLayout.MANUAL_Q, 60f).toDouble(), 0.05)
        assertTrue(
            sectionGainDb(low, 6f, EqLayout.MANUAL_Q, 20f) > 5.5f,
            "20 Hz should read nearly the full ${6f} dB, was ${sectionGainDb(low, 6f, EqLayout.MANUAL_Q, 20f)}",
        )
        val high = EqLayout.slots[6] // 14 kHz high shelf
        assertEquals(3.0, sectionGainDb(high, 6f, EqLayout.MANUAL_Q, 14_000f).toDouble(), 0.05)
    }

    /** The skip: a section at 0 dB contributes nothing, wherever it is probed. */
    @Test
    fun `a flat section contributes nothing at any frequency`() {
        for (hz in listOf(20f, 31.25f, 250f, 1_000f, 16_000f)) {
            for (slot in EqLayout.slots) {
                assertEquals(0f, sectionGainDb(slot, 0f, 0.707f, hz), "at $hz Hz")
            }
        }
    }

    // ---- curves ------------------------------------------------------------

    @Test
    fun `the flat curve is zero gains, default qs, zero preamp`() {
        val flat = EqCurve.FLAT
        for (slot in 0 until EqLayout.SLOTS) {
            assertEquals(0f, flat.gainsDb[slot])
        }
        // `==`, not the boxed equals assertEquals reaches for: the preamp is
        // computed as `-peak`, and a curve with no peak yields IEEE negative
        // zero — the same number as 0f, a different bit pattern. The mapper
        // normalizes it at the engine boundary; the model keeps the maths.
        assertTrue(flat.preampDb == 0f)
        // And rendered through the manual tab it stays zero.
        val rendered = manualCurve(EqualizerPreset.FLAT.bands)
        assertTrue(rendered.preampDb == 0f)
    }

    @Test
    fun `manual sliders clamp to their travel`() {
        val curve = manualCurve(List(EqLayout.MANUAL_COUNT) { 99f })
        for (band in 0 until EqLayout.MANUAL_COUNT) {
            assertEquals(EqLayout.MANUAL_RANGE_DB, curve.gainsDb[band])
        }
    }

    /** The pad's horizontal axis: left warm, right bright, by equal amounts. */
    @Test
    fun `the tone pad tilts without moving its middle`() {
        val corner = (EqLayout.TONE_STEPS * EqLayout.TONE_DB_PER_STEP).toDouble()
        val bright = toneCurve(x = EqLayout.TONE_STEPS, y = 0, focused = false)
        assertEquals(-corner, bright.gainsDb[EqLayout.TONE_LOW].toDouble(), 0.001)
        assertEquals(corner, bright.gainsDb[EqLayout.TONE_HIGH].toDouble(), 0.001)
        assertEquals(0f, bright.gainsDb[EqLayout.TONE_MID])
        // And the manual slots stay flat — the two tabs never sum.
        for (band in 0 until EqLayout.MANUAL_COUNT) assertEquals(0f, bright.gainsDb[band])
    }

    /** And its vertical one, which only ever touches the middle. */
    @Test
    fun `the tone pad's contour moves the mids and leaves the ends alone`() {
        val forward = toneCurve(x = 0, y = EqLayout.TONE_STEPS, focused = true)
        assertEquals(
            (EqLayout.TONE_STEPS * EqLayout.TONE_DB_PER_STEP).toDouble(),
            forward.gainsDb[EqLayout.TONE_MID].toDouble(),
            0.001,
        )
        // The untouched tilt slots hold `-tilt`, which at zero is IEEE negative
        // zero — the same number, so the comparison is `==`, not boxed equals.
        assertTrue(forward.gainsDb[EqLayout.TONE_LOW] == 0f)
        assertTrue(forward.gainsDb[EqLayout.TONE_HIGH] == 0f)
    }

    /** Focused is narrower than Broad, and that is the whole of the difference. */
    @Test
    fun `focused narrows the q and changes no gain`() {
        val broad = toneCurve(0, EqLayout.TONE_STEPS, focused = false)
        val focused = toneCurve(0, EqLayout.TONE_STEPS, focused = true)
        for (slot in 0 until EqLayout.SLOTS) {
            assertEquals(broad.gainsDb[slot], focused.gainsDb[slot])
        }
        assertTrue(focused.qs[EqLayout.TONE_MID] > broad.qs[EqLayout.TONE_MID])
        assertTrue(focused.qs[EqLayout.TONE_LOW] > broad.qs[EqLayout.TONE_LOW])
        // Narrower means the wide bell reaches further down its skirt at the
        // same setting — upstream's measured claim, sampled analytically.
        val broadSkirt = sectionGainDb(EqLayout.slots[EqLayout.TONE_MID], broad.gainsDb[EqLayout.TONE_MID], broad.qs[EqLayout.TONE_MID], 500f)
        val focusedSkirt = sectionGainDb(EqLayout.slots[EqLayout.TONE_MID], focused.gainsDb[EqLayout.TONE_MID], focused.qs[EqLayout.TONE_MID], 500f)
        assertTrue(
            broadSkirt > focusedSkirt + 1.5f,
            "broad ($broadSkirt dB) should reach further than focused ($focusedSkirt dB)",
        )
    }

    // ---- preamp ------------------------------------------------------------

    /** Attenuation only — a curve is never allowed to make itself louder. */
    @Test
    fun `the make-up attenuation never becomes a boost`() {
        for (preset in EqualizerPreset.entries) {
            if (preset == EqualizerPreset.CUSTOM) continue
            val preamp = manualCurve(preset.bands).preampDb
            assertTrue(preamp <= 0.001f, "${preset.name} asked for $preamp dB of make-up")
        }
        // Every slider up as far as it goes is the worst case there is.
        val worst = manualCurve(List(EqLayout.MANUAL_COUNT) { EqLayout.MANUAL_RANGE_DB })
        assertTrue(worst.preampDb < -1f, "full boost should ask for real attenuation, was ${worst.preampDb}")
    }

    // ---- presets -----------------------------------------------------------

    @Test
    fun `every preset covers every band`() {
        for (preset in EqualizerPreset.entries) {
            if (preset == EqualizerPreset.CUSTOM) continue
            assertEquals(
                EqLayout.MANUAL_COUNT,
                preset.bandsDb.size,
                "${preset.name} should have one value per band",
            )
        }
    }

    @Test
    fun `a preset's own bands name it, and anything else is custom`() {
        for (preset in EqualizerPreset.entries) {
            if (preset == EqualizerPreset.CUSTOM) continue
            assertEquals(preset, EqualizerPreset.matching(preset.bands), preset.name)
        }
        assertEquals(EqualizerPreset.CUSTOM, EqualizerPreset.matching(List(EqLayout.MANUAL_COUNT) { 7f }))
    }

    /** The matcher's tolerance: a hand-dragged slider that lands within a hair is still the preset. */
    @Test
    fun `bands within the matcher's tolerance still name the preset`() {
        val nudged = EqualizerPreset.ROCK.bands.map { it + 0.04f }
        assertEquals(EqualizerPreset.ROCK, EqualizerPreset.matching(nudged))
        val moved = EqualizerPreset.ROCK.bands.toMutableList().also { it[3] += 0.1f }
        assertEquals(EqualizerPreset.CUSTOM, EqualizerPreset.matching(moved))
    }

    // ---- the sweep the preamp walks ----------------------------------------

    /** The response sweep is log-spaced 20 Hz..20 kHz — the band its extremes bracket. */
    @Test
    fun `the response frequencies span the audible band`() {
        // The sweep is private; its shape is visible through the preamp it
        // computes for a curve that only lives at the extremes.
        val lowOnly = FloatArray(EqLayout.SLOTS)
        lowOnly[0] = 12f
        val lowCurve = EqCurve.of(lowOnly, FloatArray(EqLayout.SLOTS) { 0.707f })
        // A +12 dB low shelf peaks below 60 Hz, inside the sweep's span.
        assertTrue(lowCurve.preampDb < -11.5f, "was ${lowCurve.preampDb}")
        // Round-trip: the preamp is the negative of the sampled peak, to a
        // tenth — recompute the peak the same way the mapper does.
        var peak = 0f
        for (point in 0 until 96) {
            val fraction = point.toDouble() / 95.0
            val hz = (20.0 * 1000.0.pow(fraction)).toFloat()
            var sum = 0f
            for (slot in 0 until EqLayout.SLOTS) {
                sum += sectionGainDb(EqLayout.slots[slot], lowCurve.gainsDb[slot], lowCurve.qs[slot], hz)
            }
            if (sum > peak) peak = sum
        }
        assertEquals((-peak).roundToInt(), lowCurve.preampDb.roundToInt())
    }
}
