package com.music.bitchord.data.metadata

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalMediaIndexTest {

    private fun tempFile(name: String = "local_music_index.json"): java.io.File =
        Files.createTempDirectory("index").resolve(name).toFile().apply { deleteOnExit() }

    @Test
    fun `entries round-trip through the disk form`() {
        val file = tempFile()
        val entries = mapOf(
            "C:\\Music\\Artist - One.flac" to LocalMediaIndex.Entry(
                size = 12_345L,
                modifiedMs = 1_700_000_000_000L,
                title = "One",
                artist = "Artist",
                album = "Album",
                durationMs = 213_000L,
            ),
            "C:\\Music\\unreadable.mp3" to LocalMediaIndex.Entry(
                size = 10L,
                modifiedMs = 1_700_000_000_001L,
            ),
        )
        LocalMediaIndex.saveTo(file, entries)
        assertEquals(entries, LocalMediaIndex.loadFrom(file))
        assertEquals("Artist", LocalMediaIndex.loadFrom(file).values.first().toInfo().artist)
    }

    @Test
    fun `a corrupt or older index reads as empty rather than failing the scan`() {
        val corrupt = tempFile("corrupt.json").apply { writeText("{not json") }
        assertEquals(emptyMap(), LocalMediaIndex.loadFrom(corrupt))

        val older = tempFile("older.json").apply {
            writeText("""{"version":0,"entries":{"C:\\Music\\a.mp3":{"size":1,"modifiedMs":2}}}""")
        }
        assertEquals(emptyMap(), LocalMediaIndex.loadFrom(older))

        assertEquals(emptyMap(), LocalMediaIndex.loadFrom(tempFile("missing.json")))
    }
}
