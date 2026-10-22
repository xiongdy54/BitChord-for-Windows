package com.music.bitchord.desktop.playback

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The embedded-lyrics branch of the lookup, end to end against a real file
 * on disk — the seam [LyricsCoordinatorTest] stands in with a scripted
 * fetch. What this pins: a local file's own lyrics land whether or not the
 * network sources are enabled, and a file with none still falls through to
 * them.
 *
 * The bug this was written for: the embedded read sat *behind* the
 * every-source-off gate, so turning synced lyrics off — or unticking every
 * source — also switched off the words already inside the file.
 */
class LyricsCoordinatorEmbeddedTest {

    private class CountingFetch : Fetcher {
        var calls = 0
        override suspend fun lyrics(
            videoId: String,
            title: String,
            artist: String,
            durationMs: Long,
            album: String?,
            sources: Set<LyricsSource>,
            order: List<LyricsSource>,
            prioritizeSyllableSync: Boolean,
            onSourceStarted: ((LyricsSource) -> Unit)?,
            onSourceResult: ((LyricsSource, LyricsRepository.Result?) -> Unit)?,
            onSourceCancelled: ((LyricsSource) -> Unit)?,
        ): LyricsRepository.Result? {
            calls++
            return null
        }
    }

    /** Unconfined, like the coordinator's other tests — but the embedded read
     *  is a real file read on [Dispatchers.IO], so each case waits for the
     *  lookup to finish rather than assuming it ran inline. */
    private fun coordinator(fetch: CountingFetch) =
        LyricsCoordinator(CoroutineScope(Dispatchers.Unconfined), fetch)

    private suspend fun LyricsCoordinator.awaitChecked() {
        withTimeout(10_000) {
            while (!lyricsChecked.value) delay(10)
        }
    }

    private val lyrics = "[00:01.00]first line\n[00:05.00]second line"

    /** A FLAC whose VORBIS_COMMENT carries [text] under LYRICS. */
    private fun flacWithLyrics(text: String?): java.io.File {
        fun u32le(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte(),
        )

        val vendor = "test".toByteArray()
        var comment = u32le(vendor.size) + vendor
        val comments = buildList {
            add("TITLE=Test Song")
            text?.let { add("LYRICS=$it") }
        }
        comment += u32le(comments.size)
        for (c in comments) {
            val bytes = c.toByteArray(Charsets.UTF_8)
            comment += u32le(bytes.size) + bytes
        }
        fun block(type: Int, payload: ByteArray, last: Boolean) = byteArrayOf(
            ((if (last) 0x80 else 0) or type).toByte(),
            ((payload.size ushr 16) and 0xFF).toByte(),
            ((payload.size ushr 8) and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
        ) + payload

        val file = kotlin.io.path.createTempFile("embedded", ".flac").toFile()
        file.deleteOnExit()
        file.writeBytes(
            "fLaC".toByteArray() +
                block(0, ByteArray(34), last = false) +
                block(4, comment, last = true) +
                ByteArray(64),
        )
        return file
    }

    @AfterTest
    fun restoreSettings() {
        AppSettings.setSyncedLyrics(true)
    }

    @Test
    fun `a local file's own lyrics land with every source switched off`() = runBlocking {
        AppSettings.setSyncedLyrics(false)
        val fetch = CountingFetch()
        val lyricsCoordinator = coordinator(fetch)
        val file = flacWithLyrics(lyrics)

        lyricsCoordinator.loadLyrics(
            videoId = file.path,
            title = "Test Song",
            artist = "Test Artist",
            durationMs = 200_000,
            localUri = file.path,
        )
        lyricsCoordinator.awaitChecked()

        assertEquals(listOf("first line", "second line"), lyricsCoordinator.lyrics.value?.map { it.text })
        // The file answered; nobody was asked.
        assertEquals(0, fetch.calls)
    }

    @Test
    fun `a local file's own lyrics land with the sources on, without asking them`() = runBlocking {
        val fetch = CountingFetch()
        val lyricsCoordinator = coordinator(fetch)
        val file = flacWithLyrics(lyrics)

        lyricsCoordinator.loadLyrics(
            videoId = file.path,
            title = "Test Song",
            artist = "Test Artist",
            durationMs = 200_000,
            localUri = file.path,
        )
        lyricsCoordinator.awaitChecked()

        assertEquals(listOf("first line", "second line"), lyricsCoordinator.lyrics.value?.map { it.text })
        assertEquals(0, fetch.calls)
    }

    @Test
    fun `a local file with no lyrics falls through to the network`() = runBlocking {
        val fetch = CountingFetch()
        val lyricsCoordinator = coordinator(fetch)
        val file = flacWithLyrics(null)

        lyricsCoordinator.loadLyrics(
            videoId = file.path,
            title = "Test Song",
            artist = "Test Artist",
            durationMs = 200_000,
            localUri = file.path,
        )
        lyricsCoordinator.awaitChecked()

        assertEquals(1, fetch.calls)
    }

    @Test
    fun `a local file with no lyrics and the sources off is simply empty`() = runBlocking {
        AppSettings.setSyncedLyrics(false)
        val fetch = CountingFetch()
        val lyricsCoordinator = coordinator(fetch)
        val file = flacWithLyrics(null)

        lyricsCoordinator.loadLyrics(
            videoId = file.path,
            title = "Test Song",
            artist = "Test Artist",
            durationMs = 200_000,
            localUri = file.path,
        )
        lyricsCoordinator.awaitChecked()

        assertNull(lyricsCoordinator.lyrics.value)
        assertEquals(0, fetch.calls)
    }

    @Test
    fun `an unsynced tag reads as lines rather than nothing`() = runBlocking {
        val fetch = CountingFetch()
        val lyricsCoordinator = coordinator(fetch)
        val file = flacWithLyrics("first line\nsecond line")

        lyricsCoordinator.loadLyrics(
            videoId = file.path,
            title = "Test Song",
            artist = "Test Artist",
            durationMs = 200_000,
            localUri = file.path,
        )
        lyricsCoordinator.awaitChecked()

        // Untimed, but shown — verses are worth reading however they arrived.
        val lines: List<LyricLine> = requireNotNull(lyricsCoordinator.lyrics.value)
        assertEquals(listOf("first line", "second line"), lines.map { it.text })
        assertTrue(lines.all { it.timeMs == 0L })
        assertEquals(0, fetch.calls)
    }
}
