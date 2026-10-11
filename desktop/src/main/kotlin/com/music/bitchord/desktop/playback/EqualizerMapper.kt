// The curve model rendered onto libvlc's ten bands — the mapping layer that
// lets the ported model (com.music.bitchord.playback.EqCurve, verbatim
// upstream) drive VLC's built-in equalizer in place of the SVF DSP upstream
// fed directly. It lives beside the engine it dresses, not beside the model
// it reads: its output is EqualizerTuning, the shape AudioEngine.setEqualizer
// speaks, and its band table is a libvlc fact, not a curve-model one.
package com.music.bitchord.desktop.playback

import com.music.bitchord.playback.EqCurve
import com.music.bitchord.playback.EqLayout
import com.music.bitchord.playback.sectionGainDb

/**
 * The tuning an engine is asked to sound with — the desktop's replacement for
 * upstream feeding its DSP a whole [EqCurve]. Engines without an equalizer
 * ignore it (the [AudioEngine] default), [VlcAudioPlayer] maps it onto libvlc's
 * own; nothing else knows the shape exists.
 */
class EqualizerTuning(
    val enabled: Boolean,
    /** Make-up attenuation, always at or below zero — [EqCurve.preampDb]'s own rule. */
    val preampDb: Float,
    /** One gain in decibels per engine band, low to high. */
    val bandAmps: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is EqualizerTuning &&
            other.enabled == enabled &&
            other.preampDb == preampDb &&
            other.bandAmps.contentEquals(bandAmps)

    override fun hashCode(): Int = enabled.hashCode() * 31 + preampDb.hashCode() * 17 + bandAmps.contentHashCode()

    override fun toString(): String =
        "EqualizerTuning(enabled=$enabled, preamp=$preampDb, amps=${bandAmps.toList()})"

    companion object {
        /** The passthrough: nothing boosted, nothing attenuated, equalizer detached. */
        val OFF = EqualizerTuning(enabled = false, preampDb = 0f, bandAmps = FloatArray(VLC_BAND_COUNT))
    }
}

/** libvlc 3.x's equalizer has ten fixed bands, and vlcj 4.x stands on libvlc 3.x. */
const val VLC_BAND_COUNT = 10

/**
 * The ten centres libvlc's equalizer filters sit at, low to high — the same
 * table `MediaPlayerFactory.equalizer().bands()` reports, kept here so the
 * mapping stays a pure function a test can pin without a native factory. The
 * engine applies whatever count the tuning carries, so a future libvlc that
 * moved its bands would change the numbers, not the shape of the code.
 */
val VLC_BAND_CENTERS_HZ: List<Float> = listOf(
    31.25f, 62.5f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f,
)

/**
 * libvlc's own ceiling on a band gain — [uk.co.caprica.vlcj.player.base.Equalizer]
 * throws outside it, so the mapper clamps first. Upstream's curve tops out at
 * ±12 dB and its preamp only ever pulls down, so this is a safety net rather
 * than a shaping step.
 */
const val VLC_BAND_LIMIT_DB = 20f

/**
 * Renders a curve at libvlc's band centres: the whole model, sampled.
 *
 * Upstream feeds its ten SVF sections the curve directly; here the sections'
 * *analytic* magnitudes ([sectionGainDb]) are summed in decibels at each of
 * libvlc's ten fixed centres — magnitudes through a cascade multiply, so
 * decibels add, which is the same arithmetic [EqCurve]'s own preamp sweep
 * runs. The seven model bands and libvlc's ten do not line up one-to-one
 * (60 Hz…14 kHz against 31.25 Hz…16 kHz, shelves against bells), so this
 * sampling *is* the mapping: each VLC band gets the honest total the model
 * asks for at its frequency, overlapping sections included.
 *
 * What is deliberately approximate (design decision 3): Broad/Focused moves Q,
 * and a sampled sum keeps the shape of that — a narrower bell reads lower in
 * its skirts — without recreating the exact filter the DSP would have built.
 * And libvlc applies its amps as a step, where upstream's DSP chased an 18 ms
 * glide; preset switches jump rather than sweep.
 *
 * [enabled] = false is the passthrough: all-zero amps, zero preamp, and the
 * engine detaches its equalizer outright. A flat curve enabled maps to the
 * same all-zeros by construction — every section reads |gain| < 0.01 dB and
 * contributes nothing — which the mapper test pins rather than special-cases.
 */
fun equalizerTuning(
    curve: EqCurve,
    enabled: Boolean,
    bandCentersHz: List<Float> = VLC_BAND_CENTERS_HZ,
): EqualizerTuning {
    if (!enabled) return EqualizerTuning.OFF
    val amps = FloatArray(bandCentersHz.size)
    for ((band, hz) in bandCentersHz.withIndex()) {
        var sum = 0f
        for (slot in 0 until EqLayout.SLOTS) {
            sum += sectionGainDb(EqLayout.slots[slot], curve.gainsDb[slot], curve.qs[slot], hz)
        }
        amps[band] = (sum.coerceIn(-VLC_BAND_LIMIT_DB, VLC_BAND_LIMIT_DB)).normalizeZero()
    }
    val preamp = (curve.preampDb.coerceIn(-VLC_BAND_LIMIT_DB, VLC_BAND_LIMIT_DB)).normalizeZero()
    return EqualizerTuning(enabled = true, preampDb = preamp, bandAmps = amps)
}

/**
 * IEEE's negative zero, out. The ported model computes attenuation as
 * `-peak`, and a curve with nothing to attenuate hands back `-0.0` — the
 * same number as `0.0` in every arithmetic sense and a different bit pattern,
 * which the engine-facing contract ("flat maps to zero") should not hinge on.
 * `-0.0 + 0.0` is `+0.0`.
 */
private fun Float.normalizeZero(): Float = this + 0f

/**
 * Loudness normalization's gain, folded into a tuning (slice 18).
 *
 * The figure the stream resolver parsed ([com.music.bitchord.data.innertube.StreamResolver.loudnessDbFor])
 * is a "this track is N dB too loud" fact; the gain that fixes it is the negation,
 * clamped to upstream's own [-15, +3] dB window at the call site. The only gain
 * stage libvlc offers a media player that speaks decibels is its equalizer's
 * preamp — the volume percent the fader drives is a different domain and the
 * crossfade's own territory — so the gain rides there:
 *
 *  - A tuning that is **on** takes the gain added to its own preamp (the curve's
 *    make-up attenuation plus this correction), the sum clamped to libvlc's
 *    ±20 dB. The clamp is a corner, not a shape: an EQ attenuation of -12 under
 *    a -15 dB correction wants -27 and gets -20 — quieter than intended by
 *    exactly the clamp, in the one case where two attenuations stack.
 *  - A tuning that is **off** with a zero gain is returned untouched — the
 *    passthrough stays the passthrough, and nothing starts equalizing because a
 *    dial moved that had no answer for this track.
 *  - A tuning that is **off** with a real gain attaches a *flat* equalizer
 *    carrying only the preamp. Slice 15's probe established that a flat curve
 *    and a detached equalizer are bit-identical at zero preamp, so the attached
 *    flat stage is the gain and nothing else — the same "the processor is fed
 *    because the app owes the audio a change" reading upstream's chain has
 *    whenever any stage is active.
 */
fun withLoudnessGain(tuning: EqualizerTuning, gainDb: Float): EqualizerTuning {
    if (gainDb == 0f) return tuning
    return if (!tuning.enabled) {
        EqualizerTuning(
            enabled = true,
            preampDb = gainDb.coerceIn(-VLC_BAND_LIMIT_DB, VLC_BAND_LIMIT_DB).normalizeZero(),
            bandAmps = FloatArray(tuning.bandAmps.size),
        )
    } else {
        EqualizerTuning(
            enabled = true,
            preampDb = (tuning.preampDb + gainDb).coerceIn(-VLC_BAND_LIMIT_DB, VLC_BAND_LIMIT_DB).normalizeZero(),
            bandAmps = tuning.bandAmps,
        )
    }
}
