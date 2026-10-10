package com.music.bitchord.data

import com.music.bitchord.data.metadata.LocalMediaMeta
import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scan's filter table — upstream's `isEligibleLocalMusic` and the walk
 * that applies it, pinned against temp dirs. The metadata read is injected as
 * a fake, so none of this needs libvlc in the room; what is under test is the
 * cheap half (what gets read at all) and the honest half (what a row claims
 * once the reader has answered).
 */
class LocalMediaRepositoryTest {

    // ---- the eligibility gate ---------------------------------------------------------

    @Test
    fun `the extension whitelist is upstream's seven`() {
        for (ext in listOf("mp3", "m4a", "flac", "ogg", "opus", "aac", "webm")) {
            assertTrue(
                LocalMediaRepository.isEligibleLocalMusic("song.$ext", "/music/song.$ext"),
                "song.$ext should be eligible",
            )
        }
        for (ext in listOf("txt", "jpg", "m3u", "wav", "3gp", "pdf", "")) {
            assertFalse(
                LocalMediaRepository.isEligibleLocalMusic("file.$ext", "/music/file.$ext"),
                "file.$ext should not be eligible",
            )
        }
    }

    @Test
    fun `the path blacklist segments are upstream's ten`() {
        for (segment in LocalMediaRepository.nonMusicPathSegments) {
            val path = "/storage/emulated/0${segment}clip.mp3"
            assertFalse(
                LocalMediaRepository.isEligibleLocalMusic("clip.mp3", path),
                "$path should be excluded by $segment",
            )
        }
    }

    @Test
    fun `blacklist matching folds separators and case`() {
        // A Windows path with mixed case reaches the same verdict as the
        // lowercase POSIX spelling — the normalization is the whole test.
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic("x.mp3", "C:\\MUSIC\\Recordings\\x.mp3"),
        )
        assertTrue(
            LocalMediaRepository.isEligibleLocalMusic("x.mp3", "C:\\MUSIC\\Albums\\x.mp3"),
        )
    }

    @Test
    fun `a path without a drive letter still matches blacklist segments`() {
        // The leading separator is inserted, so a folder named `alarms` at the
        // root of the walk is excluded the same as a nested one.
        assertFalse(LocalMediaRepository.isEligibleLocalMusic("a.mp3", "alarms/a.mp3"))
    }

    // ---- the walk ---------------------------------------------------------------------

    private fun tempDir(name: String): java.nio.file.Path =
        java.nio.file.Files.createTempDirectory(name)

    @Test
    fun `the walk collects music and skips everything else`() {
        val root = tempDir("scan")
        fun write(relative: String, bytes: String = "x") {
            val path = root.resolve(relative)
            path.parent?.toFile()?.mkdirs()
            java.nio.file.Files.writeString(path, bytes)
        }
        write("Album One/01 - Artist - Song.mp3")
        write("Album One/02 - Artist - Other.flac")
        write("Album Two/song.m4a")
        write("notes.txt")
        write("cover.jpg")
        write("Recordings/voice.mp3") // blacklisted segment
        write("Album One/empty.opus") // zero bytes still a candidate: the scan does not open files

        val candidates = LocalMediaRepository.candidatesFor(listOf(root.toString()))
        val names = candidates.map { it.name }.toSet()
        assertEquals(
            setOf("01 - Artist - Song.mp3", "02 - Artist - Other.flac", "song.m4a", "empty.opus"),
            names,
        )
    }

    @Test
    fun `only the configured folder prefixes are scanned`() {
        val inside = tempDir("scan-inside")
        val outside = tempDir("scan-outside")
        java.nio.file.Files.writeString(inside.resolve("in.mp3"), "x")
        java.nio.file.Files.writeString(outside.resolve("out.mp3"), "x")

        val candidates = LocalMediaRepository.candidatesFor(listOf(inside.toString()))
        assertEquals(listOf("in.mp3"), candidates.map { it.name })
        assertTrue(
            LocalMediaRepository.isInSelectedFolders(inside.resolve("sub").toString(), listOf(inside.toString())),
        )
        assertFalse(LocalMediaRepository.isInSelectedFolders(outside.toString(), listOf(inside.toString())))
    }

    @Test
    fun `a missing folder scans to nothing rather than throwing`() {
        assertEquals(emptyList(), LocalMediaRepository.candidatesFor(listOf("Z:\\definitely-not-here")))
        assertEquals(emptyList(), LocalMediaRepository.candidatesFor(emptyList()))
    }

    // ---- the rows, with a fake reader ---------------------------------------------------

    private val readNothing: suspend (String) -> LocalMediaMeta.Info? = { null }

    @Test
    fun `a file the reader cannot read is still listed, named by its file`() {
        val root = tempDir("scan-meta")
        java.nio.file.Files.writeString(root.resolve("Artist - Title.mp3"), "x")
        val songs = kotlinx.coroutines.runBlocking {
            LocalMediaRepository.scan(listOf(root.toString()), readNothing)
        }
        assertEquals(1, songs.size)
        val song = songs.single()
        assertEquals(root.resolve("Artist - Title.mp3").toString(), song.videoId)
        // The filename split is the floor every row stands on, reader or not.
        assertEquals("Title", song.title)
        assertEquals("Artist", song.artist)
        assertEquals(root.resolve("Artist - Title.mp3").toString(), song.localUri)
        assertNull(song.durationText)
    }

    @Test
    fun `the thirty second gate applies only to a duration that was actually read`() {
        val root = tempDir("scan-duration")
        java.nio.file.Files.writeString(root.resolve("short.mp3"), "x")
        java.nio.file.Files.writeString(root.resolve("long.mp3"), "x")
        java.nio.file.Files.writeString(root.resolve("unknown.webm"), "x")
        val durations = mapOf(
            "short.mp3" to 10_000L,
            "long.mp3" to 90_000L,
            "unknown.webm" to null,
        )
        val songs = kotlinx.coroutines.runBlocking {
            LocalMediaRepository.scan(listOf(root.toString())) { path ->
                LocalMediaMeta.Info(
                    title = java.io.File(path).nameWithoutExtension,
                    artist = "Artist",
                    album = null,
                    durationMs = durations[java.io.File(path).name],
                )
            }
        }
        assertEquals(listOf("long", "unknown"), songs.map { it.title }.sorted())
        assertEquals("1:30", songs.single { it.durationText != null }.durationText)
    }

    @Test
    fun `filename split takes precedence and the reader fills the gaps`() {
        val root = tempDir("scan-merge")
        java.nio.file.Files.writeString(root.resolve("Artist - Title.flac"), "x")
        java.nio.file.Files.writeString(root.resolve("Just A Title.mp3"), "x")
        val songs = kotlinx.coroutines.runBlocking {
            LocalMediaRepository.scan(listOf(root.toString())) { path ->
                LocalMediaMeta.Info(
                    title = "Tagged ${java.io.File(path).name}",
                    artist = "Tagged Artist",
                    album = "Tagged Album",
                    durationMs = 61_000L,
                )
            }
        }
        // The name answered title and artist for the split file; the reader
        // contributes only what the name could not say.
        val split = songs.single { it.videoId.endsWith("Artist - Title.flac") }
        assertEquals("Title", split.title)
        assertEquals("Artist", split.artist)
        assertEquals("Tagged Album", split.albumName)
        assertEquals("1:01", split.durationText)
        val plain = songs.single { it.videoId.endsWith("Just A Title.mp3") }
        assertEquals("Just A Title", plain.title)
        assertEquals("Tagged Artist", plain.artist)
    }

    @Test
    fun `file timestamps land on the sort columns`() {
        val root = tempDir("scan-dates")
        val file = root.resolve("dated.mp3").toFile()
        file.writeBytes(ByteArray(1))
        val songs = kotlinx.coroutines.runBlocking {
            LocalMediaRepository.scan(listOf(root.toString()), readNothing)
        }
        val song = songs.single()
        assertTrue(song.localDateAddedSeconds != null || song.localDateModifiedSeconds != null)
    }

    // ---- the sort ----------------------------------------------------------------------

    private fun song(title: String, added: Long? = null, modified: Long? = null) =
        Song(
            videoId = title,
            title = title,
            artist = "A",
            thumbnailUrl = null,
            localDateAddedSeconds = added,
            localDateModifiedSeconds = modified,
        )

    @Test
    fun `the four orders match upstream's semantics`() {
        with(LocalMediaRepository) {
            val songs = listOf(song("banana"), song("Apple"), song("cherry"))
            assertEquals(
                listOf("Apple", "banana", "cherry"),
                songs.sortedForLibrary(LocalMusicSort.TITLE_ASC).map { it.title },
            )
            assertEquals(
                listOf("cherry", "banana", "Apple"),
                songs.sortedForLibrary(LocalMusicSort.TITLE_DESC).map { it.title },
            )
            val dated = listOf(
                song("old", added = 100),
                song("new", added = 300),
                song("mid", added = 200),
                song("never", added = null),
            )
            assertEquals(
                listOf("new", "mid", "old", "never"),
                dated.sortedForLibrary(LocalMusicSort.DATE_ADDED).map { it.title },
            )
            val modified = listOf(
                song("b", modified = 5),
                song("a", modified = 9),
            )
            assertEquals(
                listOf("a", "b"),
                modified.sortedForLibrary(LocalMusicSort.DATE_MODIFIED).map { it.title },
            )
            // Dates tie on the title tie-break, case-insensitively.
            val tied = listOf(song("b", added = 1), song("A", added = 1))
            assertEquals(
                listOf("A", "b"),
                tied.sortedForLibrary(LocalMusicSort.DATE_ADDED).map { it.title },
            )
        }
    }
}
