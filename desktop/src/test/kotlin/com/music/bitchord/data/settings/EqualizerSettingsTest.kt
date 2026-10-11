package com.music.bitchord.data.settings

import com.music.bitchord.data.AppFiles
import com.music.bitchord.data.FileStore
import com.music.bitchord.playback.EqLayout
import com.music.bitchord.playback.EqualizerPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The equalizer settings' persistence, exercised through the real
 * [AppSettings] — the LOCALAPPDATA redirect in build.gradle.kts keeps the
 * writes inside the build directory — and through the pure band-codec
 * functions the stored string goes through, which is where the upgrade
 * semantics live (the lyrics settings' precedent).
 *
 * Every case that writes puts the defaults back, because the object is a
 * process-wide singleton the rest of the suite reads. And every read goes
 * through a *fresh* [FileStore]: the store reads once at construction and
 * writes through, so a reader built before the writes would see nothing.
 */
class EqualizerSettingsTest {

    /** A reader opened now, not one that predates the writes under test. */
    private fun stored(): FileStore = FileStore(AppFiles.file("settings.properties"))

    @Test
    fun `the keys are upstream's, six of them, balance absent`() {
        try {
            AppSettings.setEqualizerEnabled(true)
            AppSettings.setEqualizerMode(EqualizerMode.MANUAL)
            AppSettings.setEqualizerTone(-2, 3)
            AppSettings.setEqualizerFocused(true)
            AppSettings.setEqualizerBands(EqualizerPreset.ROCK.bands)

            val stored = stored()
            // Written under the same key names the Android app persists under,
            // so a settings file means the same thing on both platforms.
            assertEquals("true", stored.getString("equalizer_enabled", "false"))
            assertEquals("MANUAL", stored.getString("equalizer_mode", "DYNAMIC"))
            assertEquals("-2", stored.getString("equalizer_tone_x", "0"))
            assertEquals("3", stored.getString("equalizer_tone_y", "0"))
            assertEquals("true", stored.getString("equalizer_focused", "false"))
            assertEquals(
                // The single-key comma encoding, upstream's own format.
                "4.0,2.5,-1.0,-1.5,1.0,3.0,3.5",
                stored.getString("equalizer_bands", ""),
            )
            // The desktop has no balance control (libvlc has no continuous
            // trim), and the file must not claim one either.
            assertNull(stored.getStringOrNull("equalizer_balance"))
        } finally {
            restoreDefaults()
        }
    }

    @Test
    fun `the mode round-trips by its enum name and an unknown falls back to dynamic`() {
        try {
            AppSettings.setEqualizerMode(EqualizerMode.MANUAL)
            assertEquals(
                EqualizerMode.MANUAL,
                stored().getStringOrNull("equalizer_mode")
                    ?.let { name -> EqualizerMode.entries.firstOrNull { it.name == name } }
                    ?: EqualizerMode.DYNAMIC,
            )
            // The same expression a future build's ghost falls through.
            assertEquals(
                EqualizerMode.DYNAMIC,
                "SOMETHING_FUTURE"
                    .let { name -> EqualizerMode.entries.firstOrNull { it.name == name } }
                    ?: EqualizerMode.DYNAMIC,
            )
        } finally {
            restoreDefaults()
        }
    }

    @Test
    fun `tone writes are clamped to the pad's travel`() {
        try {
            AppSettings.setEqualizerTone(99, -99)
            assertEquals(EqLayout.TONE_STEPS, AppSettings.equalizerToneX.value)
            assertEquals(-EqLayout.TONE_STEPS, AppSettings.equalizerToneY.value)
        } finally {
            restoreDefaults()
        }
    }

    @Test
    fun `the preset row is derived from the bands, not stored`() {
        try {
            AppSettings.setEqualizerBands(EqualizerPreset.ROCK.bands)
            assertEquals(EqualizerPreset.ROCK, AppSettings.equalizerPreset.value)
            // One slider moves: the curve is nobody's now.
            AppSettings.setEqualizerBands(
                EqualizerPreset.ROCK.bands.toMutableList().also { it[3] += 0.5f },
            )
            assertEquals(EqualizerPreset.CUSTOM, AppSettings.equalizerPreset.value)
            // Dragged back: it names the preset again instead of "Custom" forever.
            AppSettings.setEqualizerBands(EqualizerPreset.ROCK.bands)
            assertEquals(EqualizerPreset.ROCK, AppSettings.equalizerPreset.value)
            // And CUSTOM itself carries no curve — choosing it does nothing.
            AppSettings.setEqualizerPreset(EqualizerPreset.CUSTOM)
            assertEquals(EqualizerPreset.ROCK, EqualizerPreset.matching(AppSettings.equalizerBands.value))
        } finally {
            restoreDefaults()
        }
    }

    @Test
    fun `band writes clamp to the slider travel, in the flow and on disk`() {
        try {
            AppSettings.setEqualizerBands(List(EqLayout.MANUAL_COUNT) { 99f })
            assertTrue(AppSettings.equalizerBands.value.all { it == EqLayout.MANUAL_RANGE_DB })
            // A short list pads flat rather than failing — upstream's rule for
            // a backup written by a build with a different band count.
            AppSettings.setEqualizerBands(listOf(6f, 3f))
            assertEquals(listOf(6f, 3f, 0f, 0f, 0f, 0f, 0f), AppSettings.equalizerBands.value)
        } finally {
            restoreDefaults()
        }
    }

    // ---- the band codec, standalone ------------------------------------------

    @Test
    fun `the band codec round-trips the upstream comma format`() {
        val bands = EqualizerPreset.SPOKEN_WORD.bands
        val encoded = encodeEqualizerBands(clampEqualizerBands(bands))
        assertEquals(bands, decodeEqualizerBands(encoded))
        // Byte-compatible with what the Android build's `joinToString` writes.
        assertEquals(bands.joinToString(","), encoded)
    }

    @Test
    fun `decoding pads short strings and clamps over-travel values`() {
        assertEquals(listOf(6f, 0f, 0f, 0f, 0f, 0f, 0f), decodeEqualizerBands("6"))
        // Padded flat, not rejected: what a shorter backup wrote is restored,
        // and what it wrote is clamped to the sliders' travel.
        assertEquals(
            listOf(-12f, -12f, -12f, 0f, 0f, 0f, 0f),
            decodeEqualizerBands("-99,-99,-99"),
        )
    }

    @Test
    fun `decoding garbage reads as flat and null reads as never-touched`() {
        assertEquals(List(EqLayout.MANUAL_COUNT) { 0f }, decodeEqualizerBands("a,b,c"))
        assertEquals(List(EqLayout.MANUAL_COUNT) { 0f }, decodeEqualizerBands(""))
        assertEquals(List(EqLayout.MANUAL_COUNT) { 0f }, decodeEqualizerBands(null))
        assertNull(stored().getStringOrNull("key_the_app_never_wrote"))
    }

    private fun restoreDefaults() {
        AppSettings.setEqualizerEnabled(false)
        AppSettings.setEqualizerMode(EqualizerMode.DYNAMIC)
        AppSettings.setEqualizerTone(0, 0)
        AppSettings.setEqualizerFocused(false)
        AppSettings.setEqualizerBands(EqualizerPreset.FLAT.bands)
    }
}
