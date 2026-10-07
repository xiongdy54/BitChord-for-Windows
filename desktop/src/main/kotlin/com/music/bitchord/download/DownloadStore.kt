// Ported from app/src/main/java/com/music/bitchord/download/DownloadStore.kt.
//
// The naming table (`fileNameFor`/`sanitise`), the codec table (`storable`) and
// the Pending commit/abort contract are verbatim. The storage layer itself is
// rewritten for a plain file system: Android had three destinations — MediaStore,
// a legacy public Music folder, and the app's private filesDir — and a desktop
// has one. Everything lands in `AppFiles.dir("downloads")`; `IS_PENDING` becomes
// a `.part` file renamed on commit, which is the same guarantee the legacy
// branch gave and the MediaStore row gave for free. There is no media scanner
// to notify and no MIME type for the file system to refuse, so [begin] keeps
// its `mimeType` parameter only so the caller stays verbatim.
package com.music.bitchord.download

import com.music.bitchord.data.AppFiles
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * Where a downloaded track goes, and how it gets there.
 *
 * The destination is the app's own download folder, under
 * [AppFiles.root]`/downloads` — somewhere this build owns outright, which is
 * the honest desktop answer while there is no user-facing export choice. What
 * first made this class necessary upstream still applies: the bytes are never
 * written to their final name, so a cancelled download can never leave a
 * half-file that looks like a whole one.
 *
 * This class only ever copies the bytes the server on the other end sent.
 * [MediaTagger] rewrites the finished file afterwards to add tags; the filename
 * below is what every downloaded track carries regardless of whether that
 * rewrite finds a layout it recognises.
 */
object DownloadStore {

    private const val TAG = "BitChord"

    private val folder: File by lazy { AppFiles.dir("downloads") }

    // ---- Naming -------------------------------------------------------------

    /**
     * What the file is called: `Artist - Title.ext`.
     *
     * Artist first because a downloads folder is sorted by name and nothing
     * else — no tags to group by — so leading with the artist is the only thing
     * that puts an album back together in the listing.
     */
    fun fileNameFor(song: Song, extension: String): String {
        val artist = sanitise(song.artist)
        val title = sanitise(song.title)
        val stem = when {
            artist.isEmpty() -> title
            title.isEmpty() -> artist
            else -> "$artist - $title"
        }.ifEmpty { song.videoId }
        return "${stem.take(MAX_STEM_CHARS).trimEnd()}.$extension"
    }

    /**
     * Everything a FAT32 volume or a shell would each object to for its own
     * reasons, plus the whitespace that survives them.
     */
    private fun sanitise(raw: String): String = raw
        .replace(ILLEGAL, " ")
        .replace(WHITESPACE, " ")
        .trim()
        .trim('.')

    private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    private val WHITESPACE = Regex("""\s+""")

    /** Long enough for anything real, short of the 255-byte filename ceiling. */
    private const val MAX_STEM_CHARS = 120

    /** What a file of some codec is called and what a store that cared would be told. */
    class Storable(val extension: String, val mimeType: String)

    /**
     * How to file a track of [codec], or null if this build won't keep it.
     *
     * A source that can serve lossless does not thereby serve something worth
     * keeping under a name it deserves: anything not answered for here falls
     * the caller back to YouTube's AAC, so an unfamiliar codec costs quality
     * and not the download.
     *
     * Kept as a table rather than derived from the codec string because one of
     * these is not the identity mapping it looks like: ALAC ships inside an MP4
     * container, so an ALAC file is an `.m4a` as far as both the store and
     * [Mp4Tagger] are concerned — the tagger works on the box tree and never
     * asks what the samples inside are. (WAV keeps its registered
     * `audio/x-wav` even though no file system asks; the table is the record.)
     */
    fun storable(codec: String?): Storable? = when (codec?.lowercase(Locale.ROOT)?.trim()) {
        "flac", "x-flac" -> Storable("flac", "audio/flac")
        "wav", "x-wav", "wave" -> Storable("wav", "audio/x-wav")
        "alac", "m4a", "mp4", "eac3-joc", "ec3-joc", "dolby-atmos" -> Storable("m4a", "audio/mp4")
        else -> null
    }

    // ---- Lookup -------------------------------------------------------------

    /**
     * The path of a file already saved under this name, or null.
     *
     * Worth asking before every download because the file system does not
     * refuse a duplicate — a second download would land beside the first with
     * no way to tell them apart.
     */
    fun existing(name: String): String? =
        File(folder, name).takeIf { it.exists() }?.absolutePath

    /**
     * Whether [path] still names a file that is there.
     *
     * The record of what has been downloaded is kept by this app, but nothing
     * stops the user deleting the folder out from under it — an entry in the
     * record can outlive the file it names. Cheap to ask, and the answer is
     * what stops the menu offering to delete nothing.
     */
    fun exists(path: String): Boolean = runCatching { File(path).exists() }.getOrDefault(false)

    fun delete(path: String): Boolean = runCatching {
        File(path).delete()
    }.onFailure { Log.w(TAG, "could not delete $path: ${it.message}") }.getOrDefault(false)

    // ---- Writing ------------------------------------------------------------

    /**
     * A destination that exists but is not yet a file anyone can see.
     *
     * Every path out of here is either [commit] or [abort]; there is no third
     * option, because the thing being protected against is a partial file
     * surviving a failure and looking like a whole one.
     */
    class Pending internal constructor(
        /** The path the finished file will answer to — the record's value. */
        val path: String,
        val name: String,
        private val part: File,
        private val target: File,
    ) {
        /** The bytes being built — where [MediaTagger] rewrites tags before the rename. */
        val tagPath: String get() = part.absolutePath

        fun openStream(): OutputStream = part.outputStream()

        /** @return the path the finished file can be reached at. */
        fun commit(): String {
            if (!part.renameTo(target)) error("Could not finish writing $name")
            return target.absolutePath
        }

        fun abort() {
            part.delete()
        }
    }

    /**
     * Reserve [name] and return somewhere to write it.
     *
     * [mimeType] rides along for caller parity — a file system does not refuse
     * one, which is a freedom the MediaStore did not have and the reason the
     * upstream version of this function was half its length longer.
     *
     * @throws IllegalStateException if the folder can't be made — a failure
     *   worth surfacing, since it means the download cannot start rather than
     *   that it might not finish.
     */
    fun begin(name: String, mimeType: String): Pending {
        val target = File(folder, name)
        if (!folder.exists() && !folder.mkdirs()) error("Could not create ${folder.path}")
        val part = File(folder, ".$name.part")
        part.delete()
        return Pending(target.absolutePath, name, part = part, target = target)
    }
}
