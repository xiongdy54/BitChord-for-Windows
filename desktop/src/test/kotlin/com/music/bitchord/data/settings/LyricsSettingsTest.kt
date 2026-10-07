package com.music.bitchord.data.settings

import com.music.bitchord.data.FileStore
import com.music.bitchord.data.lyrics.LyricsSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The lyrics settings' upgrade semantics, pinned on the pure readers the
 * stored strings go through — an [AppSettings] case can't touch the singleton's
 * own store (see [AppSettingsTest]'s note), and these functions are where the
 * semantics actually live.
 */
class LyricsSettingsTest {

    @Test
    fun `a fresh install has every source on, in declared order`() {
        assertEquals(LyricsSource.entries.toSet(), lyricsSourcesFrom(null, null))
        assertEquals(LyricsSource.entries, lyricsSourceOrderFrom(null))
    }

    @Test
    fun `a stored-but-empty list stays an empty list`() {
        // Every source unticked is a decision, not a missing one — the empty
        // string must survive the read, not collapse into "all on". The seen
        // list names everything, which is what setLyricsSources writes.
        val seen = LyricsSource.entries.joinToString(",") { it.name }
        assertEquals(emptySet(), lyricsSourcesFrom("", seen))
    }

    @Test
    fun `a source the seen list doesn't name is enabled by an upgrade`() {
        val stored = LyricsSource.LYRICS_PLUS.name
        val upgraded = lyricsSourcesFrom(stored, seen = null)

        assertEquals(
            setOf(LyricsSource.LYRICS_PLUS) +
                LyricsSource.entries.filter { it !in LEGACY_SEEN_FALLBACK },
            upgraded,
        )
    }

    @Test
    fun `a source the user turned off stays off across an upgrade`() {
        val seen = LyricsSource.entries.joinToString(",") { it.name }
        val upgraded = lyricsSourcesFrom("LYRICS_PLUS", seen)

        assertEquals(setOf(LyricsSource.LYRICS_PLUS), upgraded)
    }

    @Test
    fun `unknown names fall out of a stored order and new sources append in declared order`() {
        val saved = listOf(LyricsSource.GENIUS, LyricsSource.LYRICS_PLUS)
        val rest = LyricsSource.entries.filter { it !in saved }

        assertEquals(
            saved + rest,
            lyricsSourceOrderFrom(saved.joinToString(",") { it.name }),
        )
        // A name no enum member answers to is an old build's ghost.
        assertEquals(
            listOf(LyricsSource.GENIUS) + LyricsSource.entries.filter { it != LyricsSource.GENIUS },
            lyricsSourceOrderFrom("GENIUS,SOURCE_THAT_NEVER_WAS"),
        )
    }

    @Test
    fun `saved-empty is distinct from never-saved`() {
        val file = File.createTempFile("bitchord-lyrics-test", ".properties").apply { deleteOnExit() }
        val fresh = FileStore(file)
        assertNull(fresh.getStringOrNull("lyrics_sources"))
        fresh.putString("lyrics_sources", "")
        assertEquals("", FileStore(file).getStringOrNull("lyrics_sources"))
    }

    private val LEGACY_SEEN_FALLBACK = setOf(
        LyricsSource.LYRICS_PLUS,
        LyricsSource.PAXSENIX,
        LyricsSource.BETTER_LYRICS,
        LyricsSource.SIMP_MUSIC,
        LyricsSource.KUGOU,
        LyricsSource.LRCLIB,
        LyricsSource.MUSIXMATCH,
        LyricsSource.GENIUS,
    )
}
