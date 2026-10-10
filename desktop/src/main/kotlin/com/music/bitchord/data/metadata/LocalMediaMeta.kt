package com.music.bitchord.data.metadata

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.remote.RemoteSong
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.media.Media
import uk.co.caprica.vlcj.media.Meta
import uk.co.caprica.vlcj.media.MediaParsedStatus
import uk.co.caprica.vlcj.media.ParseFlag
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import kotlinx.coroutines.delay

/**
 * The read side of local-file metadata — the desktop's
 * `MediaMetadataRetriever`, built on the one media library this build already
 * ships: vlcj. The write side (the `MediaTagger` family) predates it and
 * stays write-only.
 *
 * Two halves, in the priority order the scan needs:
 *
 *  * **The filename.** `Artist - Title.ext` splits the way
 *    [RemoteSong.splitArtistTitle] splits it for every remote library — one
 *    convention everywhere, and the only source that never blocks on native
 *    code.
 *  * **vlcj's parse.** A media is opened, parsed with a native timeout, and
 *    released; whatever tags libvlc reads off the container fill the fields
 *    the filename could not. The parse is a blocking native call, so it runs
 *    bounded — one [MediaPlayerFactory] per [Session] (never the engine's),
 *    a [Semaphore] over concurrency, and libvlc's own parse timeout as the
 *    per-file leash — and every call is `runCatching`-wrapped, because a
 *    file that cannot be read is a row with a filename for a title, not a
 *    failed scan.
 *
 * Never fakes a value: a field nothing read stays null and the caller
 * decides what that is worth (see [com.music.bitchord.data.LocalMediaRepository]).
 */
object LocalMediaMeta {

    private const val TAG = "BitChord"

    /** How many files parse at once. Native parses are CPU-heavy; four is the discipline the design names. */
    private const val PARSE_CONCURRENCY = 4

    /**
     * libvlc's per-media parse leash, in milliseconds. Passed to
     * `libvlc_media_parse_with_options`, so the bound is enforced natively —
     * a hung demuxer answers TIMEOUT rather than holding a worker forever,
     * which a coroutine timeout could not promise of a blocking JNI call.
     */
    private const val PARSE_TIMEOUT_MS = 10_000

    /** How often the parse status is polled while libvlc works. */
    private const val PARSE_POLL_INTERVAL_MS = 5L

    /** Everything the scan asks of one file. Any member may be absent. */
    data class Info(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
    ) {
        /** The filename split, for callers that had no tags worth reading. */
        companion object {
            fun fromFileName(fileName: String): Info {
                val base = fileName.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: fileName
                val (artist, title) = RemoteSong.splitArtistTitle(base)
                return Info(
                    title = title ?: base.takeIf { it.isNotBlank() },
                    artist = artist,
                    album = null,
                    durationMs = null,
                )
            }
        }
    }

    /**
     * One scan's parsing handle. The factory is the expensive native object —
     * created once per scan and released with it, rather than once per file
     * and never — and one session at a time keeps the concurrency limiter
     * honest.
     */
    class Session private constructor(
        private val factory: MediaPlayerFactory?,
    ) {
        /**
         * The coroutine semaphore, not `java.util.concurrent`'s — the acquire
         * here suspends rather than parks its thread, and the difference is
         * the whole reason this object exists in this shape: a scan asks for
         * every file at once, and a blocking acquire inside those coroutines
         * deadlocked the pool on the first real folder (every IO worker parked
         * on the semaphore while the four permit-holders sat in `delay`,
         * waiting for a worker to resume on — a deadlock no test folder was
         * large enough to trip, which the first full-machine scan found).
         */
        private val permits = Semaphore(PARSE_CONCURRENCY)

        /**
         * The native read: whatever tags libvlc parsed off the container,
         * raw — no filename mixing. The filename-first merge over these
         * happens where every row passes regardless of who read the tags
         * (the scan's [com.music.bitchord.data.LocalMediaRepository] builds
         * its rows through the same seam tests inject fakes into), so the
         * precedence rules live in exactly one place.
         */
        suspend fun read(path: String): Info = withContext(Dispatchers.IO) {
            parse(path) ?: Info(null, null, null, null)
        }

        /**
         * The native half, on the limiter. Null when there is nothing to
         * say — no factory, a file libvlc cannot open, or a parse that
         * neither succeeded nor had meta anyway.
         */
        private suspend fun parse(path: String): Info? {
            val factory = factory ?: return null
            permits.acquire()
            try {
                var media: Media? = null
                try {
                    media = factory.media().newMedia(path)
                    if (media == null) return null
                    // Local files only ever need the local demux: no network
                    // fetch flag, so a file claiming a network resource
                    // cannot turn a directory scan into a crawler.
                    //
                    // `parse` with a timeout is libvlc's *asynchronous*
                    // options call: it kicks libvlc's own parse thread and
                    // returns immediately — verified against vlcj 4.12.1's
                    // bytecode, and the reason a first cut of this read came
                    // back instant and empty. The wait below is what makes
                    // the read synchronous, bounded by the same leash.
                    if (!media.parsing().parse(PARSE_TIMEOUT_MS, ParseFlag.PARSE_LOCAL)) {
                        Log.d(TAG, "meta parse request refused for $path")
                        return null
                    }
                    val deadline = System.currentTimeMillis() + PARSE_TIMEOUT_MS
                    while (true) {
                        when (media.parsing().status()) {
                            MediaParsedStatus.DONE, MediaParsedStatus.SKIPPED -> {}
                            MediaParsedStatus.FAILED -> {
                                Log.d(TAG, "meta parse failed for $path")
                                return null
                            }
                            MediaParsedStatus.TIMEOUT -> {
                                Log.d(TAG, "meta parse timed out for $path")
                                return null
                            }
                            else -> {
                                if (System.currentTimeMillis() >= deadline) {
                                    Log.d(TAG, "meta parse wait expired for $path")
                                    return null
                                }
                                delay(PARSE_POLL_INTERVAL_MS)
                                continue
                            }
                        }
                        break
                    }
                    val meta = media.meta()
                    val title = meta.get(Meta.TITLE)
                    val artist = meta.get(Meta.ARTIST)
                    val album = meta.get(Meta.ALBUM)
                    // Duration lives off the parsed media, not the meta map.
                    // -1 is libvlc's "unknown"; zero is no better.
                    val duration = media.info().duration().takeIf { it > 0 }
                    if (title.isNullOrBlank() && artist.isNullOrBlank() &&
                        album.isNullOrBlank() && duration == null
                    ) {
                        return null
                    }
                    return Info(title, artist, album, duration)
                } catch (error: Throwable) {
                    Log.d(TAG, "meta read failed for $path: ${error.message}")
                    return null
                } finally {
                    runCatching { media?.release() }
                }
            } finally {
                permits.release()
            }
        }

        fun close() {
            runCatching { factory?.release() }
        }

        companion object {
            /**
             * Opens a session, or a null-factory one when libvlc is not on
             * the machine — the scan degrades to filename rows rather than
             * refusing to run. `--quiet` keeps libvlc's banner off the log.
             */
            fun open(): Session = Session(
                runCatching { MediaPlayerFactory("--quiet") }
                    .onFailure { Log.w(TAG, "no libvlc for metadata parsing; filenames only: ${it.message}") }
                    .getOrNull(),
            )
        }
    }

    private fun String?.cleanTag(): String? =
        takeUnless { it.isNullOrBlank() || it.equals("<unknown>", ignoreCase = true) }

    /** [ms] as the row's `M:SS` display string, the way the queue prints durations. */
    fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }
}
