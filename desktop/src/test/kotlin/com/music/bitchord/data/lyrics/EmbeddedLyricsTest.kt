package com.music.bitchord.data.lyrics

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reader against bytes real taggers produce — not the round trip with
 * this app's own writers, which [com.music.bitchord.EmbeddedLyricsTest] on
 * the Android side owns. A local folder is the user's own: an MP3 tagged by
 * Mp3tag or MusicBee, an Ogg exported by ffmpeg, a tag whose lyrics are
 * plain text because nobody timed them. Each of those used to read as
 * "no lyrics" and the player went to the network for words already on disk.
 *
 * Placeholder text throughout; no real lyric appears in this file.
 */
class EmbeddedLyricsTest {

    private val unsynced = "first line\nsecond line\n\nchorus line"
    private val synced = "[00:01.00]first line\n[00:05.00]second line\n[00:09.00]chorus line"

    // ── builders — the smallest tag shape a real tagger writes ──────────────

    private fun u32be(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    /** Vorbis comments count their lengths little-endian — the one part of FLAC that isn't BE. */
    private fun u32le(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte(),
    )

    /** A v2.3 frame: id, plain 32-bit size, two flag bytes, payload. */
    private fun frameV23(id: String, payload: ByteArray) =
        id.toByteArray() + u32be(payload.size) + byteArrayOf(0, 0) + payload

    /** A v2.4 frame: id, syncsafe size, two flag bytes, payload. */
    private fun frameV24(id: String, payload: ByteArray) =
        id.toByteArray() + syncsafe(payload.size) + byteArrayOf(0, 0) + payload

    /** A v2.2 frame: three-character id, plain 24-bit size, payload. */
    private fun frameV22(id: String, payload: ByteArray) =
        id.toByteArray() + byteArrayOf(
            ((payload.size ushr 16) and 0xFF).toByte(),
            ((payload.size ushr 8) and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
        ) + payload

    private fun syncsafe(v: Int) = byteArrayOf(
        ((v ushr 21) and 0x7F).toByte(), ((v ushr 14) and 0x7F).toByte(),
        ((v ushr 7) and 0x7F).toByte(), (v and 0x7F).toByte(),
    )

    private fun textPayload(text: String) = byteArrayOf(0) + text.toByteArray() + byteArrayOf(0)

    /** `USLT`: encoding, language, descriptor, text — the shape v2.3 and v2.4 share. */
    private fun usltPayload(text: String, encoding: Int = 0, v22: Boolean = false): ByteArray {
        val body = when (encoding) {
            0 -> text.toByteArray(Charsets.ISO_8859_1)
            1 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
            3 -> text.toByteArray(Charsets.UTF_8)
            else -> text.toByteArray(Charsets.ISO_8859_1)
        }
        // An empty descriptor is one terminator — two for UTF-16.
        val terminator = if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0)
        // The language field is v2.3's; a v2.2 ULT has none.
        val language = if (v22) byteArrayOf() else "eng".toByteArray()
        return byteArrayOf(encoding.toByte()) + language + terminator + body
    }

    private fun id3File(version: Int, vararg frames: ByteArray): ByteArray {
        val tag = frames.fold(byteArrayOf()) { acc, b -> acc + b }
        return "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + syncsafe(tag.size) + tag +
            // A little audio after the tag, the way a real file has.
            byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(64)
    }

    private fun mp3(version: Int, lyrics: String, lyricsEncoding: Int = 0): ByteArray {
        fun frame(id: String, payload: ByteArray) = when (version) {
            2 -> frameV22(id.take(3), payload)
            4 -> frameV24(id, payload)
            else -> frameV23(id, payload)
        }
        val v22 = version == 2
        val lyricsId = if (v22) "ULT" else "USLT"
        return id3File(
            version,
            frame("TIT2", textPayload("Test Song")),
            frame("TPE1", textPayload("Test Artist")),
            frame(lyricsId, usltPayload(lyrics, lyricsEncoding, v22 = v22)),
        )
    }

    // ── FLAC ───────────────────────────────────────────────────────────────

    private fun flacBlock(type: Int, payload: ByteArray, last: Boolean) = byteArrayOf(
        ((if (last) 0x80 else 0) or type).toByte(),
        ((payload.size ushr 16) and 0xFF).toByte(),
        ((payload.size ushr 8) and 0xFF).toByte(),
        (payload.size and 0xFF).toByte(),
    ) + payload

    private fun vorbisComment(comments: List<String>): ByteArray {
        val vendor = "reference libFLAC".toByteArray()
        var out = u32le(vendor.size) + vendor + u32le(comments.size)
        for (c in comments) {
            val bytes = c.toByteArray(Charsets.UTF_8)
            out += u32le(bytes.size) + bytes
        }
        return out
    }

    private fun flac(lyrics: String) =
        "fLaC".toByteArray() +
            flacBlock(0, ByteArray(34), last = false) +
            flacBlock(4, vorbisComment(listOf("TITLE=Test Song", "LYRICS=$lyrics")), last = true) +
            ByteArray(64)

    // ── MP4 / M4A ──────────────────────────────────────────────────────────

    private fun box(type: String, vararg payloads: ByteArray): ByteArray {
        val body = payloads.fold(byteArrayOf()) { acc, b -> acc + b }
        return u32be(8 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private fun dataBox(kind: Int, payload: ByteArray) =
        // indicator(4) + locale(4) + value — the shape the reader and every
        // real tagger agree on.
        box("data", u32be(kind) + byteArrayOf(0, 0, 0, 0) + payload)

    private fun m4a(lyrics: String) =
        box("ftyp", "isom".toByteArray()) +
            box(
                "moov",
                box("udta", box("meta", byteArrayOf(0, 0, 0, 0) + box(
                    "ilst",
                    box("©nam", dataBox(1, "Test Song".toByteArray())),
                    box("©lyr", dataBox(1, lyrics.toByteArray(Charsets.UTF_8))),
                ))),
            ) +
            ByteArray(64)

    // ── Ogg ────────────────────────────────────────────────────────────────

    private fun oggPage(payload: ByteArray, seq: Int): ByteArray {
        val segs = mutableListOf<Int>()
        var remaining = payload.size
        while (remaining >= 255) {
            segs.add(255)
            remaining -= 255
        }
        segs.add(remaining)
        // 27 bytes: capture(4) + version(1) + flags(1) + granule(8) +
        // serial(4) + sequence(4) + checksum(4) + segment count(1).
        val header = "OggS".toByteArray() + byteArrayOf(0, 0) +
            ByteArray(8) + // granule position
            byteArrayOf(1, 0, 0, 0) + // serial
            u32be(seq) +
            u32be(0) + // checksum — the reader does not check it
            byteArrayOf(segs.size.toByte())
        return header + segs.fold(byteArrayOf()) { acc, s -> acc + byteArrayOf(s.toByte()) } + payload
    }

    private fun oggCommentPacket(comments: List<String>): ByteArray {
        val vendor = "Xiph.Org libVorbis".toByteArray()
        var out = byteArrayOf(3) + "vorbis".toByteArray() + u32le(vendor.size) + vendor +
            u32le(comments.size)
        for (c in comments) {
            val bytes = c.toByteArray(Charsets.UTF_8)
            out += u32le(bytes.size) + bytes
        }
        return out
    }

    private fun ogg(lyrics: String) =
        oggPage(byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(40), seq = 0) +
            oggPage(oggCommentPacket(listOf("TITLE=Test Song", "LYRICS=$lyrics")), seq = 1) +
            oggPage(byteArrayOf(5) + "vorbis".toByteArray() + ByteArray(32), seq = 2) +
            ByteArray(64)

    private fun opus(lyrics: String) =
        oggPage("OpusHead".toByteArray() + ByteArray(20), seq = 0) +
            oggPage(opusTagsPacket(lyrics), seq = 1) +
            ByteArray(64)

    private fun opusTagsPacket(lyrics: String): ByteArray {
        val vendor = "libopus".toByteArray()
        var out = "OpusTags".toByteArray() + u32le(vendor.size) + vendor + u32le(1)
        val comment = "LYRICS=$lyrics".toByteArray(Charsets.UTF_8)
        out += u32le(comment.size) + comment
        return out
    }

    // ── the read ───────────────────────────────────────────────────────────

    /** [bytes] written to a temp file and read the way the player reads one. */
    private fun linesOf(bytes: ByteArray): List<LyricLine>? {
        val file = kotlin.io.path.createTempFile("lyrics", ".tag").toFile().apply { deleteOnExit() }
        file.writeBytes(bytes)
        return runBlocking { EmbeddedLyrics.forFile(file.path) }
    }

    @Test
    fun `an mp3's USLT reads, plain or timed, in all three tag versions`() {
        for (version in listOf(2, 3, 4)) {
            val plain = requireNotNull(linesOf(mp3(version, unsynced)))
            assertEquals(listOf("first line", "second line", "", "chorus line"), plain.map { it.text })
            assertTrue(plain.all { it.timeMs == 0L })

            val timed = requireNotNull(linesOf(mp3(version, synced)))
            assertEquals(listOf("first line", "second line", "chorus line"), timed.map { it.text })
            assertEquals(listOf(1_000L, 5_000L, 9_000L), timed.map { it.timeMs })
        }
    }

    @Test
    fun `a UTF-16 USLT decodes`() {
        val lines = requireNotNull(linesOf(mp3(3, unsynced, lyricsEncoding = 1)))
        assertEquals("first line", lines.first().text)
    }

    @Test
    fun `an mp3 with a cover and no lyrics frame reads as nothing`() {
        val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)
        val apic = byteArrayOf(0) + "image/jpeg\u0000".toByteArray() + byteArrayOf(3) + "c\u0000".toByteArray() + cover
        val file = id3File(
            3,
            frameV23("TIT2", textPayload("Test Song")),
            frameV23("APIC", apic),
        )
        // The cover is a picture, not a lyric — reading it as one would hand
        // a JPEG back as words.
        assertNull(EmbeddedLyrics.fromBytes(file))
    }

    @Test
    fun `a flac's LYRICS comment reads, plain or timed`() {
        assertEquals(listOf("first line", "second line", "", "chorus line"), requireNotNull(linesOf(flac(unsynced))).map { it.text })
        assertEquals(listOf(1_000L, 5_000L, 9_000L), requireNotNull(linesOf(flac(synced))).map { it.timeMs })
    }

    @Test
    fun `stamps carrying a numeric suffix keep their timing and words`() {
        // QQ-style tags write `[mm:ss.xx-N]` — an index beside the stamp. The
        // suffix is not part of the time; the line is still timed lyrics, and
        // reading it as plain text left the stamp visible in the sheet.
        val raw = "[00:00.00-1] first line\n[00:05.00-2] second line"
        val lines = requireNotNull(linesOf(flac(raw))).filterNot { it.isGap }
        assertEquals(listOf(0L, 5_000L), lines.map { it.timeMs })
        assertEquals(listOf("first line", "second line"), lines.map { it.text })
    }

    @Test
    fun `an m4a's lyr atom reads, plain or timed`() {
        assertEquals(listOf("first line", "second line", "", "chorus line"), requireNotNull(linesOf(m4a(unsynced))).map { it.text })
        assertEquals(listOf(1_000L, 5_000L, 9_000L), requireNotNull(linesOf(m4a(synced))).map { it.timeMs })
    }

    @Test
    fun `an ogg's LYRICS comment reads`() {
        assertEquals(listOf("first line", "second line", "", "chorus line"), requireNotNull(linesOf(ogg(unsynced))).map { it.text })
        assertEquals(listOf(1_000L, 5_000L, 9_000L), requireNotNull(linesOf(ogg(synced))).map { it.timeMs })
    }

    @Test
    fun `an opus file's LYRICS comment reads`() {
        assertEquals(listOf("first line", "second line", "", "chorus line"), requireNotNull(linesOf(opus(unsynced))).map { it.text })
    }

    @Test
    fun `a tag with stamps but no words reads as nothing, not as plain text`() {
        // An instrumental break: the stamps are there, the words are not, and
        // the plain-text path must not turn the stamps themselves into lines.
        assertNull(linesOf(flac("[00:01.00]\n[00:05.00]")))
    }

    @Test
    fun `a file with no lyrics at all reads back as nothing`() {
        // A tagged file with no lyrics field — the comment holds title and
        // artist, and nothing that reads as words.
        val comment = vorbisComment(listOf("TITLE=Test Song", "ARTIST=Test Artist"))
        val file = "fLaC".toByteArray() +
            flacBlock(0, ByteArray(34), last = false) +
            flacBlock(4, comment, last = true) +
            ByteArray(64)
        assertNull(linesOf(file))
        assertNull(EmbeddedLyrics.fromBytes(ByteArray(512) { it.toByte() }))
        assertNull(EmbeddedLyrics.fromBytes(ByteArray(8) { 0 }))
    }

    @Test
    fun `the app's own word-timed field still wins where a container carries it`() {
        // BITCHORD_LYRICS in a FLAC comment — the enhanced A2 form, written by
        // the download path — must still come back ahead of a plain LYRICS.
        val comment = vorbisComment(
            listOf(
                "LYRICS=[00:01.00]plain",
                "BITCHORD_LYRICS=[00:01.00]<00:01.00>word <00:01.40>timed",
            ),
        )
        val file = "fLaC".toByteArray() +
            flacBlock(0, ByteArray(34), last = false) +
            flacBlock(4, comment, last = true)
        val lines = LrcLib.parseLrc(requireNotNull(EmbeddedLyrics.fromBytes(file)))
        assertEquals("word timed", lines.single().text)
    }
}
