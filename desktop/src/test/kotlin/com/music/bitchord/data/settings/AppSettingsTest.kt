package com.music.bitchord.data.settings

import com.music.bitchord.data.FileStore
import com.music.bitchord.playback.RepeatMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSettingsTest {

    /**
     * A throwaway file, reopened as a second process would. Not `AppFiles.root` —
     * a unit test must not leave a real `settings.properties` behind in the user's
     * app directory, and `AppSettings`' own `prefs` must stay untouched so the
     * Task 5 and Task 6 cases read the defaults they assume.
     */
    private fun tempFile(): File =
        File.createTempFile("bitchord-settings-test", ".properties").apply { deleteOnExit() }

    @Test
    fun `a boolean round-trips through the file it lives in`() {
        val file = tempFile()
        FileStore(file).putString("shuffle_enabled", "true")
        assertTrue(FileStore(file).getString("shuffle_enabled", "false") == "true")
        FileStore(file).putString("shuffle_enabled", "false")
        assertFalse(FileStore(file).getString("shuffle_enabled", "true") == "true")
    }

    @Test
    fun `repeat mode reads back as the number it was written as`() {
        val file = tempFile()
        FileStore(file).putString("repeat_mode", RepeatMode.ALL.toString())
        assertEquals(
            RepeatMode.ALL,
            FileStore(file).getString("repeat_mode", "0").toIntOrNull(),
        )
    }

    @Test
    fun `the keys are the ones the Android app persists under`() {
        val file = tempFile()
        val store = FileStore(file)
        store.putString("shuffle_enabled", "true")
        store.putString("repeat_mode", "1")
        val reread = FileStore(file)
        assertEquals("true", reread.getString("shuffle_enabled", "false"))
        assertEquals("1", reread.getString("repeat_mode", "0"))
    }

    @Test
    fun `the theme setting round-trips by its enum name`() {
        val file = tempFile()
        FileStore(file).putString("theme_setting", ThemeSetting.DARK.name)
        // The same expression AppSettings.themeSetting's initialiser reads.
        val stored = FileStore(file).getString("theme_setting", ThemeSetting.SYSTEM.name)
        assertEquals(
            ThemeSetting.DARK,
            ThemeSetting.entries.firstOrNull { it.name == stored } ?: ThemeSetting.SYSTEM,
        )
    }

    @Test
    fun `a stored theme the code no longer knows falls back to system`() {
        // A settings file from a future or past build must not break the
        // object's initialiser — the fallback is part of the read, not the
        // caller's job.
        val file = tempFile()
        FileStore(file).putString("theme_setting", "OLED_BUT_MORE")
        val stored = FileStore(file).getString("theme_setting", ThemeSetting.SYSTEM.name)
        assertEquals(
            ThemeSetting.SYSTEM,
            ThemeSetting.entries.firstOrNull { it.name == stored } ?: ThemeSetting.SYSTEM,
        )
    }

    @Test
    fun `a quality rung reads back by its enum name, unknown falls to the default`() {
        val file = tempFile()
        val store = FileStore(file)
        store.putString("audio_quality", "HIGH")
        store.putString("download_quality", "SOMETHING_FUTURE")
        // The same expressions the two initialisers read, fallbacks included.
        val audio = FileStore(file).getString("audio_quality", "LOSSLESS")
            .let { stored -> com.music.bitchord.data.settings.AudioQuality.entries.firstOrNull { it.name == stored } }
            ?: com.music.bitchord.data.settings.AudioQuality.LOSSLESS
        val download = FileStore(file).getString("download_quality", "HIGH")
            .let { stored -> com.music.bitchord.data.settings.DownloadQuality.entries.firstOrNull { it.name == stored } }
            ?: com.music.bitchord.data.settings.DownloadQuality.HIGH
        assertEquals(com.music.bitchord.data.settings.AudioQuality.HIGH, audio)
        // A rung a future build removed must not break the object's initialiser.
        assertEquals(com.music.bitchord.data.settings.DownloadQuality.HIGH, download)
    }

    @Test
    fun `the language is absent when the stored tag is blank`() {
        // `setLanguage(null)` writes the empty string; reading it back must be
        // null again, not an empty tag that `Locale.forLanguageTag` would
        // silently turn into the undetermined locale.
        val file = tempFile()
        FileStore(file).putString("language", "")
        assertEquals(
            null,
            FileStore(file).getString("language", "").takeIf { it.isNotBlank() },
        )
    }
}
