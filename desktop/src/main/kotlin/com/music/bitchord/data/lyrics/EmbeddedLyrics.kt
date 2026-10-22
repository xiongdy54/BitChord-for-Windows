// Ported from app/src/main/java/com/music/bitchord/data/lyrics/EmbeddedLyrics.kt.
//
// The container parsers, the sidecar and `fromBytes` — the parts the round-trip
// tests own — are verbatim. The Android half of the read (a `content://` uri and
// its resolver) collapses into a plain file: a download's address on this
// platform is a path, and `forUri(context, uri)` becomes `forFile(path)`.
package com.music.bitchord.data.lyrics

import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * The lyrics already sitting inside a downloaded file.
 *
 * The read side of what the download path wrote — see `MediaTagger`, and the
 * three taggers under it. A track that was downloaded had its lyrics fetched
 * once, at download time, and written into the file; asking four servers for
 * them again every time it is played is a network round trip to arrive at a
 * string that is already on disk, and it is the reason a downloaded song showed
 * nothing at all with the connection off.
 *
 * Two fields are read, in this order:
 *
 *  - `BITCHORD_LYRICS`, this app's own, holding the "enhanced" A2 form with the
 *    word timings intact — see [toEnhancedLrc].
 *  - the container's standard lyrics field, holding plain `[mm:ss.xx]` LRC.
 *
 * The second is what every other player reads and what older downloads have,
 * so it is the fallback rather than the exception. Preferring the first is what
 * keeps a downloaded song lighting up word by word instead of a line at a time.
 *
 * Never throws. A file that isn't one of the containers, or is one and
 * has no lyrics in it, is a null — the caller falls back to the network, which
 * is exactly what it did before this existed.
 *
 * ### The local library
 *
 * The download path writes three containers — M4A, FLAC, WebM — but a local
 * folder is the user's own, and the two most common tags in it are an ID3v2
 * `USLT` in an MP3 and a `LYRICS` comment in an Ogg. Both are read here too,
 * because "the file already knows the words" is the whole point and an MP3
 * is not a second-class citizen for it.
 *
 * A tag that carries plain text — no `[mm:ss.xx]` stamps — is not thrown away
 * either: those come back as unsynced lines, the shape [Genius] answers in,
 * because verses worth reading are worth reading whether or not anyone timed
 * them. See [forFile].
 *
 * ### Offline packages
 *
 * One kind of download has no container to have been written into: an HLS or
 * DASH stream is saved as a directory of fMP4 segments behind a playlist, and
 * its `localUri` points at that playlist. `OfflineHls` and `OfflineDash` write
 * the lyrics beside it as a `lyrics.lrc` sidecar for exactly that reason — so
 * [sidecar] is tried first, and a package's lyrics come back the same shape as
 * a tagged file's. Without it those tracks fetched their lyrics from the
 * network on every play and showed none at all offline, which is the failure
 * this whole object exists to prevent.
 */
object EmbeddedLyrics {

    private const val TAG = "BitChord"

    /**
     * Most bytes worth pulling to find a tag.
     *
     * A cap rather than a size: this reads whatever region of the file holds
     * the metadata, and that region is small in all three containers — but its
     * length is stated *by the file*, so a corrupt or hostile one could claim
     * any number at all. `LyricsTag` caps what it writes at 64k, so anything
     * past this is not a tag this app produced.
     */
    private const val MAX_TAG_BYTES = 8 * 1024 * 1024

    /**
     * The lyrics inside the file at [path], or null when it has none worth
     * showing.
     *
     * Touches the filesystem, so it runs on [Dispatchers.IO] regardless of
     * where it is called from.
     */
    suspend fun forFile(path: String): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val raw = runCatching { read(File(path)) }
                .onFailure { Log.d(TAG, "no embedded lyrics in $path: ${it.message}") }
                .getOrNull()
                ?: return@withContext null
            // The same last pass the network sources get, so a downloaded track
            // and a streamed one draw their backing vocals the same way.
            //
            // Stamped lines where there are any — and where there are none,
            // the text as unsynced lines rather than nothing. The stamp
            // filter in [LrcLib.parseLrc] is right for a database answer,
            // which is always timed; a tag written by a tagger usually is
            // not, and dropping the words for that hid lyrics the file
            // plainly carried. A file that has stamps but no words in them
            // is an instrumental break, and stays nothing.
            val stamped = LrcLib.parseLrc(raw)
            val lines = when {
                stamped.any { it.text.isNotBlank() } -> stamped
                !LRC_STAMP.containsMatchIn(raw) -> plainLines(raw)
                else -> emptyList()
            }
            lines.takeIf { it.any { it.text.isNotBlank() } }
                ?.withBackgroundVocals()
        }

    /** The raw LRC text for the file, preferring this app's word-timed field. */
    private fun read(file: File): String? =
        sidecar(file.absolutePath)
            ?: file.takeIf(File::exists)?.inputStream()
                ?.use { fromBytes(it.readAtMost(MAX_TAG_BYTES)) }

    /**
     * [raw] as unsynced lines — the shape [Genius.textToLyricLines] answers
     * in, so a tag with no timestamps reads like a plain-text source rather
     * than like nothing at all. Blank runs collapse to one gap and the ends
     * are trimmed, which is what keeps a verse from opening on a break.
     *
     * Every line sits at zero: nothing timed them, and inventing times would
     * have the sweep light up lines the singer never reached. The lyric view
     * already knows how to draw an unsynced sheet — it draws Genius's the
     * same way.
     */
    private fun plainLines(raw: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        var lastWasGap = false
        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (!lastWasGap && result.isNotEmpty()) {
                    result.add(LyricLine(timeMs = 0L, text = ""))
                    lastWasGap = true
                }
            } else {
                result.add(LyricLine(timeMs = 0L, text = line))
                lastWasGap = false
            }
        }
        while (result.isNotEmpty() && result.first().isGap) result.removeAt(0)
        while (result.isNotEmpty() && result.last().isGap) result.removeAt(result.lastIndex)
        return result
    }

    /**
     * The `lyrics.lrc` written next to the offline package playlist at [path].
     *
     * Recognised by the playlist rather than by the directory it sits in: the
     * package lives under a path this module has no business knowing, and a
     * local `.m3u8` is only ever one of these packages — nothing else in the
     * app saves a playlist to disk. What the sidecar holds is the enhanced A2
     * form where the download found one, which is why it is preferred over a
     * container search rather than used as a fallback to it.
     *
     * Takes the path rather than the `Uri` it came out of so the pairing with
     * the writers can be tested on a real directory, without a device.
     */
    internal fun sidecar(path: String?): String? {
        val playlist = path?.let(::File)?.takeIf { it.name.endsWith(".m3u8", ignoreCase = true) }
            ?: return null
        val file = File(playlist.parentFile ?: return null, "lyrics.lrc")
        // Bounded for the same reason [MAX_TAG_BYTES] is: this is read into a
        // string, and the size on disk is the only thing that says how big one.
        if (!file.isFile || file.length() !in 1..MAX_TAG_BYTES.toLong()) return null
        return runCatching { file.readText() }
            .onFailure { Log.d(TAG, "could not read $file: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * The raw LRC text in [head], whichever of the containers it is.
     *
     * Split from [read] so the parsing can be tested against bytes a tagger
     * just produced, without a device or a `Context` in the way — the round
     * trip is the only thing that proves a reader and a writer agree.
     *
     * Five containers, not three: the download path's own (MP4, FLAC,
     * Matroska) plus the two a local folder is full of (ID3v2, Ogg).
     */
    internal fun fromBytes(head: ByteArray): String? {
        val found = when {
            head.startsWith(FLAC_MAGIC) -> flac(head)
            head.startsWith(MATROSKA_MAGIC) -> matroska(head)
            head.isMp4() -> mp4(head)
            head.startsWith(ID3_MAGIC) -> id3(head)
            head.startsWith(OGG_MAGIC) -> ogg(head)
            else -> null
        }
        return found?.takeIf { it.isNotBlank() }
    }

    // ---- MP4 / M4A ----------------------------------------------------------

    /**
     * The `©lyr` atom's text, or this app's freeform one where it is present.
     *
     * Both live under `moov/udta/meta/ilst`, and the search is scoped to `moov`
     * rather than run over the file: a four-byte pattern turns up in audio data
     * often enough that scanning the whole thing would eventually read a frame
     * as a tag. Inside `moov` the same pattern is a tag or it is nothing.
     */
    private fun mp4(bytes: ByteArray): String? {
        val moov = topLevelBox(bytes, "moov") ?: return null
        // Exclusive, and deliberately so: the lyrics are the last item written
        // into `ilst`, so their value ends exactly on `moov`'s own end — an
        // inclusive bound here rejects the one atom this is looking for.
        val end = moov.last + 1
        return ilstText(bytes, moov.first, end, freeform = true)
            ?: ilstText(bytes, moov.first, end, freeform = false)
    }

    /**
     * The bounds of a top-level box, without walking into it.
     *
     * `moov` is not required to come before the audio — a file written without
     * the faststart pass puts it after `mdat` — so this steps box to box rather
     * than assuming a position.
     */
    private fun topLevelBox(bytes: ByteArray, type: String): IntRange? {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val declared = readU32(bytes, pos)
            var headerLen = 8
            var size = declared
            if (declared == 1L) {
                if (pos + 16 > bytes.size) return null
                size = readU64(bytes, pos + 8)
                headerLen = 16
            } else if (declared == 0L) {
                size = (bytes.size - pos).toLong()
            }
            if (size < headerLen || size > Int.MAX_VALUE) return null
            val end = (pos + size).toInt().coerceAtMost(bytes.size)
            if (String(bytes, pos + 4, 4, Charsets.ISO_8859_1) == type) return pos until end
            pos += size.toInt()
        }
        return null
    }

    /**
     * The text of the lyrics item inside [within].
     *
     * [freeform] picks which of the two: this app's `----` item, whose name is
     * carried in a `name` box beside the value, or the standard `©lyr`, whose
     * four-byte type *is* the name. They are stored differently enough that one
     * search cannot find both.
     */
    private fun ilstText(bytes: ByteArray, from: Int, endExclusive: Int, freeform: Boolean): String? {
        val marker = if (freeform) WORD_LYRICS_FIELD.toByteArray(Charsets.UTF_8) else LYR_ATOM
        var at = from
        while (true) {
            val found = bytes.indexOf(marker, at, endExclusive) ?: return null
            // The value is the first `data` box after the name, in both layouts:
            // a freeform item is mean/name/data, a standard one is type/data.
            val data = bytes.indexOf(DATA_ATOM, found, endExclusive) ?: return null
            dataText(bytes, data, endExclusive)?.let { return it }
            at = found + marker.size
        }
    }

    /**
     * An iTunes `data` box's payload as text.
     *
     * The box is version/flags(4) + locale(4) + the value, and the length in
     * front of it is what says where the value stops — a lyric sheet has
     * newlines in it and nothing else terminates it.
     */
    private fun dataText(bytes: ByteArray, dataAt: Int, endExclusive: Int): String? {
        val start = dataAt - 4
        if (start < 0 || dataAt + 12 > endExclusive) return null
        val size = readU32(bytes, start).toInt()
        if (size <= 16 || start + size > endExclusive) return null
        // Type indicator 1 is UTF-8 text; a cover's 13/14 is the other thing a
        // `data` box holds, and decoding a JPEG as a string is not a lyric.
        if (readU32(bytes, dataAt + 4).toInt() != 1) return null
        return String(bytes, dataAt + 12, start + size - (dataAt + 12), Charsets.UTF_8)
    }

    // ---- ID3v2 (MP3) ----------------------------------------------------------

    /**
     * The `USLT` frame's text — `ULT` on a v2.2 tag, whose frame ids are three
     * characters. The field every tagger writes plain lyrics to and every
     * other player reads; a tagger that times them writes LRC text into it,
     * which [LrcLib.parseLrc] then times the same way it times a database
     * answer.
     *
     * Walked frame by frame off the tag's own size, the way the MP3 half of
     * `EmbeddedArt` walks it for a cover: the size in the header is the only
     * thing that says where the frames stop, padding ends the walk, and a
     * frame whose size cannot be trusted ends it too rather than reading
     * audio as a tag.
     */
    private fun id3(bytes: ByteArray): String? {
        val version = bytes[3].toInt() and 0xFF
        if (version !in 2..4) return null
        var tagSize = syncsafe(bytes, 6)
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) return null
        // The tag cannot outrun the bytes actually read, whatever it claims.
        tagSize = tagSize.coerceAtMost(bytes.size - 10)
        if (tagSize <= 0) return null
        val lyricsId = if (version == 2) "ULT" else "USLT"
        val idLen = if (version == 2) 3 else 4
        val headLen = if (version == 2) 6 else 10
        // An extended header declares its own size, and both conventions for
        // what that size counts ship in the wild — the same four candidates
        // the MP3 art walk tries, first one that lands on a frame id wins.
        var pos = 0
        if (bytes[5].toInt() and 0x40 != 0 && bytes.size >= 14) {
            val declared = if (version == 4) syncsafe(bytes, 10) else readU32(bytes, 10).toInt()
            pos = listOf(declared, declared - 4, 6, 10)
                .firstOrNull { it in 0..tagSize && framesStart(bytes, 10 + it, version) }
                ?: return null
        }
        while (pos + headLen <= tagSize) {
            val at = 10 + pos
            val id = bytes.decodeToString(at, at + idLen)
            if (id.all { it == '\u0000' }) break
            val size = when (version) {
                2 -> u24(bytes, at + 3).toInt()
                4 -> syncsafe(bytes, at + 4)
                else -> readU32(bytes, at + 4).toInt()
            }
            if (size <= 0 || pos + headLen + size > tagSize) break
            if (id == lyricsId) return usltText(bytes, at + headLen, size, version)
            if (!id.all { it.isLetterOrDigit() }) break
            pos += headLen + size
        }
        return null
    }

    /** Whether [at] looks like the first frame of a tag rather than its middle. */
    private fun framesStart(bytes: ByteArray, at: Int, version: Int): Boolean {
        if (at < 0 || at + 4 > bytes.size) return false
        if (bytes.copyOfRange(at, minOf(at + 4, bytes.size)).all { it == 0.toByte() }) return true
        val idLen = if (version == 2) 3 else 4
        if (at + idLen > bytes.size) return false
        return bytes.decodeToString(at, at + idLen).all { it.isLetterOrDigit() }
    }

    /**
     * The lyrics out of a `USLT`/`ULT` frame's payload: encoding, language,
     * a content descriptor, then the text. The language and the descriptor
     * carry nothing a reader wants, and the descriptor is empty often enough
     * that the terminator is the whole of it.
     *
     * The language field is v2.3's addition — a v2.2 `ULT` goes straight
     * from the encoding byte to the descriptor.
     */
    private fun usltText(bytes: ByteArray, at: Int, size: Int, version: Int): String? {
        if (size < 5) return null
        val encoding = bytes[at].toInt() and 0xFF
        val end = at + size
        // UTF-16 terminates on a double null; everything else on a single one.
        val unit = if (encoding == 1 || encoding == 2) 2 else 1
        var pos = at + 1 + if (version == 2) 0 else 3
        while (pos + unit <= end) {
            val zero = unit == 1 || bytes[pos + 1].toInt() == 0
            if (bytes[pos].toInt() == 0 && zero) {
                pos += unit
                break
            }
            pos += unit
        }
        if (pos >= end) return null
        val body = bytes.copyOfRange(pos, end)
        if (body.isEmpty()) return null
        return when (encoding) {
            0 -> body.toString(Charsets.ISO_8859_1)
            1 -> body.toString(Charsets.UTF_16) // BOM-marked
            2 -> body.toString(Charsets.UTF_16BE)
            3 -> body.toString(Charsets.UTF_8)
            else -> body.toString(Charsets.ISO_8859_1)
        }
    }

    // ---- Ogg Vorbis / Opus ----------------------------------------------------

    /**
     * The `LYRICS` comment out of the comment header — the same field, in the
     * same VORBIS_COMMENT layout, that a FLAC carries. Ogg's is a packet
     * rather than a metadata block, so the first pages are walked to it:
     * identification first, the comment header second, and a packet that
     * names itself (`\x03vorbis`, `OpusTags`) is the one this wants.
     *
     * Pages are walked rather than assumed at an offset because the comment
     * header is not required to sit in the second page, only early — and a
     * packet spanning pages is assembled before it is read, the way the Ogg
     * art walk assembles one.
     */
    private fun ogg(bytes: ByteArray): String? {
        var at = 0
        var pages = 0
        var packet = ByteArray(0)
        while (pages < 16 && at + 27 <= bytes.size) {
            pages++
            if (bytes[at] != OGG_MAGIC[0] || bytes[at + 1] != OGG_MAGIC[1] ||
                bytes[at + 2] != OGG_MAGIC[2] || bytes[at + 3] != OGG_MAGIC[3]
            ) {
                return null
            }
            val continued = bytes[at + 5].toInt() and 0x01 != 0
            val segCount = bytes[at + 26].toInt() and 0xFF
            val tableAt = at + 27
            if (tableAt + segCount > bytes.size) return null
            var bodyLen = 0
            for (i in 0 until segCount) bodyLen += bytes[tableAt + i].toInt() and 0xFF
            val bodyAt = tableAt + segCount
            if (bodyAt + bodyLen > bytes.size) return null
            val body = bytes.copyOfRange(bodyAt, bodyAt + bodyLen)
            packet = if (continued) packet + body else body
            at = bodyAt + bodyLen
            // A segment table ending short of 255 closes the packet.
            val endsHere = segCount == 0 || bytes[tableAt + segCount - 1].toInt() and 0xFF < 255
            if (endsHere) {
                if (packet.size > 8 && packet[0].toInt() == 0x03 &&
                    packet.copyOfRange(1, 7).decodeToString() == "vorbis"
                ) {
                    return vorbisComment(packet, 7, packet.size)
                }
                if (packet.size > 8 && packet.copyOfRange(0, 8).decodeToString() == "OpusTags") {
                    return vorbisComment(packet, 8, packet.size)
                }
                packet = ByteArray(0)
                // Past the fourth page and no comment header: the file has
                // nothing to say here, and the walk stops rather than
                // reading audio for the rest of the song.
                if (pages > 4) return null
            }
        }
        return null
    }

    // ---- FLAC ---------------------------------------------------------------

    /**
     * The `LYRICS` (or [WORD_LYRICS_FIELD]) comment out of the `VORBIS_COMMENT` block.
     *
     * Every length in the block is **little-endian** — it reuses Ogg Vorbis'
     * layout, which is the one part of FLAC that isn't big-endian.
     */
    private fun flac(bytes: ByteArray): String? {
        var pos = FLAC_MAGIC.size
        while (pos + 4 <= bytes.size) {
            val flags = bytes[pos].toInt() and 0xFF
            val length = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val start = pos + 4
            if (start + length > bytes.size) return null
            if (flags and 0x7F == FLAC_VORBIS_COMMENT) {
                return vorbisComment(bytes, start, start + length)
            }
            if (flags and 0x80 != 0) return null
            pos = start + length
        }
        return null
    }

    private fun vorbisComment(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        fun u32(): Int? {
            if (pos + 4 > end) return null
            val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        val vendor = u32() ?: return null
        pos += vendor
        val count = u32() ?: return null
        var plain: String? = null
        repeat(count.coerceAtMost(4_096)) {
            val length = u32() ?: return plain
            if (length < 0 || pos + length > end) return plain
            val entry = String(bytes, pos, length, Charsets.UTF_8)
            pos += length
            val name = entry.substringBefore('=').uppercase()
            val value = entry.substringAfter('=', "")
            // This app's own field wins outright; the standard one is held in
            // case it turns out to be the only one there.
            if (name == WORD_LYRICS_FIELD && value.isNotBlank()) return value
            if (name == "LYRICS" && plain == null && value.isNotBlank()) plain = value
        }
        return plain
    }

    // ---- Matroska / WebM ----------------------------------------------------

    /**
     * The `LYRICS` SimpleTag's string.
     *
     * Scanned for rather than walked down to: the tags a download writes are
     * appended after everything else (see `WebmTagger`), so reaching them
     * properly would mean parsing the whole Segment — every cluster of audio —
     * to arrive at the last few hundred bytes. The name is matched inside a
     * `TagName` element and the value read out of the `TagString` that follows,
     * so this is looking at tag structure rather than guessing at loose bytes.
     */
    private fun matroska(bytes: ByteArray): String? {
        var plain: String? = null
        for (name in listOf(WORD_LYRICS_FIELD, "LYRICS")) {
            val needle = name.toByteArray(Charsets.US_ASCII)
            var from = 0
            while (true) {
                val at = bytes.indexOf(needle, from, bytes.size) ?: break
                from = at + needle.size
                // The name element's own header sits immediately in front of it:
                // id(2) + a one-byte length for a name this short.
                if (at < 3 || bytes[at - 3] != ID_TAGNAME[0] || bytes[at - 2] != ID_TAGNAME[1]) continue
                if ((bytes[at - 1].toInt() and 0x7F) != needle.size) continue
                val string = bytes.indexOf(ID_TAGSTRING, from, bytes.size) ?: continue
                val size = readVint(bytes, string + 2) ?: continue
                val valueAt = string + 2 + size.width
                if (size.value <= 0 || valueAt + size.value > bytes.size) continue
                val value = String(bytes, valueAt, size.value.toInt(), Charsets.UTF_8)
                if (value.isBlank()) continue
                if (name == WORD_LYRICS_FIELD) return value
                if (plain == null) plain = value
            }
        }
        return plain
    }

    private class Vint(val value: Long, val width: Int)

    /**
     * An EBML variable-length integer: the highest set bit of the first byte
     * gives the width, and the bits after it are the value.
     */
    private fun readVint(bytes: ByteArray, offset: Int): Vint? {
        if (offset >= bytes.size) return null
        val first = bytes[offset].toInt() and 0xFF
        if (first == 0) return null
        var width = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            width++
        }
        if (offset + width > bytes.size) return null
        var value = (first and mask.inv() and 0xFF).toLong()
        for (i in 1 until width) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return Vint(value, width)
    }

    // ---- Bytes --------------------------------------------------------------

    /** Reads up to [max] bytes, which is all of a normal file and a prefix of a huge one. */
    private fun InputStream.readAtMost(max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 1 shl 16))
        val buffer = ByteArray(1 shl 16)
        var total = 0
        while (total < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    /** `ftyp` at offset 4 is what says "this is an MP4" — there is no leading magic. */
    private fun ByteArray.isMp4(): Boolean =
        size > 12 && this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
            this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()

    private fun ByteArray.indexOf(needle: ByteArray, from: Int, until: Int): Int? {
        if (needle.isEmpty()) return null
        val last = minOf(until, size) - needle.size
        var i = from.coerceAtLeast(0)
        outer@ while (i <= last) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return null
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    /** The 24-bit big-endian at [off] — an ID3v2.2 frame's size field. */
    private fun u24(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 16) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            (b[off + 2].toLong() and 0xFF)

    /**
     * The syncsafe integer at [off] — seven bits a byte, the size encoding
     * ID3v2.4 states and the tag header always uses.
     */
    private fun syncsafe(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return -1
        return ((b[off].toInt() and 0x7F) shl 21) or ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or (b[off + 3].toInt() and 0x7F)
    }

    private const val FLAC_VORBIS_COMMENT = 4
    private val FLAC_MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)
    private val MATROSKA_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val ID3_MAGIC = "ID3".toByteArray(Charsets.US_ASCII)
    private val OGG_MAGIC = "OggS".toByteArray(Charsets.US_ASCII)
    private val DATA_ATOM = "data".toByteArray(Charsets.ISO_8859_1)
    private val LYR_ATOM = byteArrayOf(0xA9.toByte()) + "lyr".toByteArray(Charsets.ISO_8859_1)
    private val ID_TAGNAME = byteArrayOf(0x45, 0xA3.toByte())
    private val ID_TAGSTRING = byteArrayOf(0x44, 0x87.toByte())

    /**
     * An `[mm:ss.xx]` stamp anywhere in the text — what says a tag holds
     * timed lyrics rather than plain ones, so the plain-text path can tell
     * "no stamps" apart from "stamps, but no words".
     */
    private val LRC_STAMP = Regex("""\[\d+:\d+(\.\d+)?(?:-\d+)?]""")
}
