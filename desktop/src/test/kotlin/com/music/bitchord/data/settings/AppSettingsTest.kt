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
}
