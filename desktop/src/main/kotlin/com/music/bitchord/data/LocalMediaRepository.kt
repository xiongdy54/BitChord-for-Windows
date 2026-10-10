// Ported from app/src/main/java/com/music/bitchord/data/LocalMediaRepository.kt — the scan
// half. The desktop has no MediaStore, so the cursor becomes a filesystem walk, but the
// filter is upstream's verbatim: the same extension whitelist, the same path blacklist
// segments, the same 30-second threshold, and (like the WebDAV BFS) the same 10k cap.
// `getDownloadedSongs` is not ported — the desktop Downloads page (slice 6) reads its own
// record directly. Upstream's shape is kept: no background infrastructure, no persistence,
// scan on page entry.
//
// The id convention is upstream's, transposed: a local song's `videoId` **is** the address
// of the file (upstream: the content URI string, L283/L408; here: the absolute path).
package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.metadata.LocalMediaMeta
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LocalMusicSort
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object LocalMediaRepository {

    private const val TAG = "BitChord"

    /** Files shorter than this are notifications and clips, not music — upstream's own gate. */
    const val MIN_LOCAL_MUSIC_DURATION_MS = 30_000L

    /** The scan's ceiling, the WebDAV BFS's own — a root chosen badly must not walk forever. */
    const val MAX_FILES = 10_000

    val localMusicExtensions = setOf(
        "mp3", "m4a", "flac", "ogg", "opus", "aac", "webm",
    )

    val nonMusicPathSegments = listOf(
        "/alarms/",
        "/notifications/",
        "/ringtones/",
        "/podcasts/",
        "/audiobooks/",
        "/recordings/",
        "/voice recorder/",
        "/sound_recorder/",
        "/call_rec/",
        "/whatsapp voice notes/",
    )

    /**
     * The device's local music, as [Song] rows. A scan-and-forget read: nothing
     * is persisted, nothing cached, exactly like upstream's page-entry scan —
     * the page re-asks every time it is opened and the disk is the answer.
     *
     * Metadata comes through [LocalMediaMeta]: the filename split first, vlcj
     * for what the name could not say. A file whose tags cannot be read is
     * still listed — its name is its title — and a file whose duration cannot
     * be read skips the 30-second gate rather than guessing one, because a
     * wrong duration excludes a real track and an unknown one does not.
     */
    suspend fun getLocalMusic(): List<Song> = withContext(Dispatchers.IO) {
        val session = LocalMediaMeta.Session.open()
        try {
            scan(AppSettings.localMusicFolders.value) { session.read(it) }
        } finally {
            session.close()
        }
    }

    /**
     * The walk itself, with the metadata read injected so the filter and the
     * row shape can be tested against temp dirs without libvlc in the room.
     * Candidates are gathered in one BFS pass, then parsed [readMeta] in
     * parallel — the reader bounds its own concurrency, so the scan simply
     * asks for every file at once and lets the limiter pace it.
     */
    suspend fun scan(
        folders: List<String>,
        readMeta: suspend (String) -> LocalMediaMeta.Info?,
    ): List<Song> = coroutineScope {
        val candidates = candidatesFor(folders)
        candidates.map { file ->
            async {
                buildSong(file, readMeta(file.absolutePath))
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * The files a scan of [folders] would read, before any metadata is asked
     * for. Whitelist, blacklist and folder membership are all decided here, on
     * cheap path strings — the expensive read happens only for what survives.
     */
    internal fun candidatesFor(folders: List<String>): List<File> {
        val out = ArrayList<File>()
        for (folder in folders) {
            val root = File(folder)
            if (!root.isDirectory) continue
            val queue = ArrayDeque<File>()
            queue.add(root)
            while (queue.isNotEmpty() && out.size < MAX_FILES) {
                val dir = queue.removeFirst()
                val children = runCatching { dir.listFiles() }
                    .onFailure { Log.w(TAG, "cannot list ${dir.absolutePath}: ${it.message}") }
                    .getOrNull() ?: continue
                for (child in children.sortedBy { it.absolutePath.lowercase(Locale.ROOT) }) {
                    if (out.size >= MAX_FILES) break
                    if (child.isDirectory) {
                        queue.add(child)
                    } else if (isEligibleLocalMusic(
                            fileName = child.name,
                            path = child.absolutePath,
                        )
                    ) {
                        out.add(child)
                    }
                }
            }
        }
        return out
    }

    /**
     * Whether [fileName] under [path] can be music at all — decided without
     * opening the file. Upstream's `isEligibleLocalMusic` minus the duration
     * column: MediaStore stated a duration per row; here the duration is known
     * only after the file is read, so the 30-second gate is applied by
     * [buildSong] once the reader has answered.
     *
     * The blacklist is upstream's verbatim, including the voice-recorder
     * spellings — those directories exist under whatever name the phone's
     * vendor chose, and a desktop folder synced from one inherits it.
     */
    internal fun isEligibleLocalMusic(fileName: String, path: String?): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in localMusicExtensions) return false

        val normalizedPath = path
            ?.replace('\\', '/')
            ?.lowercase(Locale.ROOT)
            ?.let { if (it.startsWith("/")) it else "/$it" }
            ?: return true
        return nonMusicPathSegments.none(normalizedPath::contains)
    }

    private suspend fun buildSong(
        file: File,
        info: LocalMediaMeta.Info?,
    ): Song? {
        val path = file.absolutePath
        // The duration gate, applied only when a duration is actually known.
        // A parse failure is not a 29-second ringtone, and must not read as one.
        val durationMs = info?.durationMs
        if (durationMs != null && durationMs < MIN_LOCAL_MUSIC_DURATION_MS) return null

        // The filename split first, whatever read the tags: the name is the
        // one source every row has, and the same convention every remote
        // library rows by. A reader's answer fills only the fields the name
        // left open — album and duration, plus title/artist when the split
        // could not produce them.
        val fromName = LocalMediaMeta.Info.fromFileName(file.name)
        val title = fromName.title
            ?: info?.title?.cleanTag()
            ?: return null
        val artist = fromName.artist
            ?: info?.artist?.cleanTag()
            ?: "Unknown Artist"

        val dates = fileTimestamps(file)

        return Song(
            videoId = path,
            title = title,
            artist = artist,
            thumbnailUrl = null,
            durationText = durationMs?.let { LocalMediaMeta.formatDuration(it) },
            albumName = info?.album?.cleanTag(),
            localUri = path,
            localPath = path,
            localDateAddedSeconds = dates.first,
            localDateModifiedSeconds = dates.second,
        )
    }

    private fun String?.cleanTag(): String? =
        takeUnless { it.isNullOrBlank() || it.equals("<unknown>", ignoreCase = true) }

    /**
     * MediaStore's DATE_ADDED/DATE_MODIFIED pair, from the filesystem's own
     * attributes: creation time for added (the closest thing a file has to
     * "when it arrived"), modification time for modified. A filesystem that
     * answers neither leaves both null rather than one faked from the other.
     */
    private fun fileTimestamps(file: File): Pair<Long?, Long?> = runCatching {
        val attrs: BasicFileAttributes = Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
        )
        Pair(
            attrs.creationTime().toMillis().takeIf { it > 0 }?.div(1_000),
            attrs.lastModifiedTime().toMillis().takeIf { it > 0 }?.div(1_000),
        )
    }.getOrElse { Pair(null, null) }

    /**
     * The folder-prefix filter, upstream's [isInSelectedFolder] with the SAF
     * tree decoding gone: the desktop stores absolute paths, so membership is
     * a normalized prefix test. Case-folding and separator folding are the
     * whole subtlety — a Windows user picks `C:\Music` and the scan reports
     * `c:\music\…`, and a comparison that says those differ excludes
     * everything.
     */
    internal fun isInSelectedFolders(path: String, folders: List<String>): Boolean {
        if (folders.isEmpty()) return false
        val candidate = path.normalizedPath() ?: return false
        return folders.any { folder ->
            val normalized = folder.normalizedPath()
            normalized != null &&
                (candidate == normalized || candidate.startsWith("$normalized/"))
        }
    }

    private fun String.normalizedPath(): String? =
        takeIf { it.isNotBlank() }?.replace('\\', '/')?.trimEnd('/')?.lowercase(Locale.ROOT)

    /**
     * The library sort, upstream's `sortedForLibrary` carried over whole —
     * the same four orders, the same tie-break on title, so a folder sorted
     * on the phone sorts identically here.
     */
    internal fun List<Song>.sortedForLibrary(order: LocalMusicSort): List<Song> = when (order) {
        LocalMusicSort.TITLE_ASC -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        LocalMusicSort.TITLE_DESC -> sortedWith(compareByDescending<Song> { it.title.lowercase(Locale.ROOT) })
        LocalMusicSort.DATE_ADDED -> sortedWith(
            compareByDescending<Song> { it.localDateAddedSeconds ?: Long.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
        LocalMusicSort.DATE_MODIFIED -> sortedWith(
            compareByDescending<Song> { it.localDateModifiedSeconds ?: Long.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    }
}
