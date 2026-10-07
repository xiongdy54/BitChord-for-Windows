// Ported from app/src/test/java/com/music/bitchord/DownloadStoreTest.kt.
//
// The metadata, badge, naming and codec-table cases are verbatim. What stayed
// behind, with the layers that owned them: the source-request cases
// (`SourceResolver.requestForDownload` — no source layer on this platform), the
// Wi-Fi-only gate and the cellular split (no metered concept), and the
// detail/perTrack halves of the describability case (the desktop rung carries
// its label only).
package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.download.SavedSongMetadata
import com.music.bitchord.download.resolvedDownloadDates
import com.music.bitchord.download.youtubeDownloadBadge
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The decisions a download makes before a byte is fetched: what the file is
 * called, whether it is worth keeping one at all, and what quality goes into
 * it.
 *
 * The first two are worth pinning because both fail late and badly. A wrong
 * extension or MIME type is not a compile error and not a bad-sounding download
 * — upstream it was `IllegalArgumentException: Unsupported MIME type` from
 * inside a `ContentResolver.insert`, several frames from anything naming the
 * track, which is precisely how every download in a build once failed while
 * reporting itself as a connection problem. The file system is kinder, but the
 * table is the record, and a codec nobody answers for must fall through to
 * YouTube's AAC rather than land as a file nothing can open.
 *
 * The last one is worth pinning because it fails silently instead. A download
 * capped by the wrong setting is a file that plays perfectly and is not what
 * was asked for, and nothing about it looks wrong from outside.
 */
class DownloadStoreTest {

    private fun song(title: String, artist: String, videoId: String = "abc123") =
        Song(videoId = videoId, title = title, artist = artist, thumbnailUrl = null)

    @Test
    fun `old download metadata remains readable without an added date`() {
        val old = Json.decodeFromString<SavedSongMetadata>(
            """{"videoId":"abc","title":"Song","artist":"Artist","uri":"file:///song.webm"}""",
        )

        assertNull(old.dateAddedSeconds)
    }

    @Test
    fun `existing downloads use file time when no creation time was recorded`() {
        val (added, modified) = resolvedDownloadDates(
            persistedAddedSeconds = null,
            fileModifiedMillis = 1_725_000_123_999,
        )

        assertEquals(1_725_000_123L, added)
        assertEquals(1_725_000_123L, modified)
    }

    @Test
    fun `persisted creation time survives later file modifications`() {
        val (added, modified) = resolvedDownloadDates(
            persistedAddedSeconds = 1_700_000_000,
            fileModifiedMillis = 1_725_000_123_999,
        )

        assertEquals(1_700_000_000L, added)
        assertEquals(1_725_000_123L, modified)
    }

    // ---- What the store will keep -------------------------------------------

    @Test
    fun `flac and wav map to the types the store's table answers for`() {
        val flac = DownloadStore.storable("flac")
        assertEquals("flac", flac?.extension)
        assertEquals("audio/flac", flac?.mimeType)

        // Not audio/x-wav's mirror image: the x- prefix is on the MIME type
        // here and not on the extension.
        val wav = DownloadStore.storable("wav")
        assertEquals("wav", wav?.extension)
        assertEquals("audio/x-wav", wav?.mimeType)
    }

    @Test
    fun `alac is filed as the mp4 it actually is`() {
        val alac = DownloadStore.storable("alac")
        assertEquals("m4a", alac?.extension)
        assertEquals("audio/mp4", alac?.mimeType)
    }

    @Test
    fun `codecs are matched however a source spells them`() {
        assertEquals("flac", DownloadStore.storable("FLAC")?.extension)
        assertEquals("flac", DownloadStore.storable(" x-flac ")?.extension)
    }

    @Test
    fun `an unknown or absent codec is nothing to file`() {
        // Every one of these falls the download through to YouTube's AAC, so
        // answering with a guess here would cost a file nothing can open.
        assertNull(DownloadStore.storable(null))
        assertNull(DownloadStore.storable(""))
        assertNull(DownloadStore.storable("opus"))
        assertNull(DownloadStore.storable("webm"))
        assertNull(DownloadStore.storable("dsf"))
    }

    @Test
    fun `youtube downloads retain their codec and resolved bitrate`() {
        assertEquals("OPUS · 160 kbps", youtubeDownloadBadge("webm", 160))
        assertEquals("AAC · 128 kbps", youtubeDownloadBadge("m4a", 128))
        assertNull(youtubeDownloadBadge("webm", 0))
    }

    // ---- What the file is called -------------------------------------------

    @Test
    fun `the name is artist then title, and carries the extension asked for`() {
        assertEquals(
            "Arijit Singh - Kesariya.flac",
            DownloadStore.fileNameFor(song("Kesariya", "Arijit Singh"), "flac"),
        )
    }

    @Test
    fun `characters a volume or a shell would object to are replaced`() {
        val name = DownloadStore.fileNameFor(song("A/B: C?", "D|E"), "m4a")
        assertEquals("D E - A B C.m4a", name)
    }

    @Test
    fun `a row with nothing to name it falls back to the video id`() {
        assertEquals("xyz789.m4a", DownloadStore.fileNameFor(song("", "", "xyz789"), "m4a"))
    }

    // ---- What quality is kept ----------------------------------------------

    /**
     * The download ladder caps its rungs in kbps — High is 256, a ceiling the
     * resolve actually asks YouTube under — and only Lossless is uncapped. The
     * sentinel must not survive into a resolve as a literal 2-billion-kbps
     * number: the difference between a ceiling nothing exceeds and no ceiling
     * at all is invisible until something starts formatting it.
     */
    @Test
    fun `only the lossless rung is uncapped`() {
        assertEquals(256, com.music.bitchord.data.settings.DownloadQuality.HIGH.maxKbps)
        assertEquals(
            Int.MAX_VALUE,
            com.music.bitchord.data.settings.DownloadQuality.LOSSLESS.maxKbps,
        )
    }

    /** Every rung has to say what it is, or the picker shows a blank line. */
    @Test
    fun `every download rung is describable`() {
        com.music.bitchord.data.settings.DownloadQuality.entries.forEach { quality ->
            assertTrue(quality.label.isNotBlank())
        }
    }
}
