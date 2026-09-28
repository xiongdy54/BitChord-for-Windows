package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * The app's own record of how each track came to be playing, as text you can
 * paste somewhere.
 *
 * Desktop port of the Android class of the same name. The lines go to stdout
 * as well as into the buffer below, so `./gradlew -p desktop run` shows the
 * resolve path live and the buffer is what a future Copy Log button would
 * read.
 *
 * ### What gets kept
 *
 * Only the paths that decide how a track plays: the resolver, the source
 * ladder and, once it exists, the cache and the player. Deliberately not the
 * feeds, the artwork, the lyrics or the library — a paste that includes
 * everything is one nobody reads to the end of, and none of it has ever been
 * the answer to "why did this song sound wrong".
 *
 * ### Which lines are whose
 *
 * Every line is filed against the track it is about, and reading the log back
 * is a question about a track rather than about a stretch of time — see
 * [about] and [forTrack]. A track is resolved while the one before it plays,
 * so "the last thirty seconds of log" is never the same thing as "this song's
 * story".
 */
object TrackLog {

    // ── Writing ─────────────────────────────────────────────────────────────

    fun d(tag: String, message: String, about: String? = working.get()) {
        DebugLog.d(tag, message)
        record('D', message, about)
    }

    fun i(tag: String, message: String, about: String? = working.get()) {
        DebugLog.i(tag, message)
        record('I', message, about)
    }

    fun w(tag: String, message: String, about: String? = working.get()) {
        DebugLog.w(tag, message)
        record('W', message, about)
    }

    fun w(tag: String, message: String, error: Throwable, about: String? = working.get()) {
        DebugLog.w(tag, message, error)
        record('W', "$message\n${error.stackTraceToString()}", about)
    }

    fun e(tag: String, message: String, about: String? = working.get()) {
        DebugLog.e(tag, message)
        record('E', message, about)
    }

    fun e(tag: String, message: String, error: Throwable, about: String? = working.get()) {
        DebugLog.e(tag, message, error)
        record('E', "$message\n${error.stackTraceToString()}", about)
    }

    // ── Whose line is it ────────────────────────────────────────────────────

    /** The track whose work this thread is doing, if it is doing any. */
    private val working = ThreadLocal<String?>()

    /**
     * A coroutine context that files everything logged inside it against [id].
     *
     *     scope.async(Dispatchers.IO + TrackLog.about(videoId)) { … }
     *
     * Carried as a [kotlinx.coroutines.ThreadContextElement] rather than a bare
     * thread local because that work hops threads constantly, and this follows
     * it, including into every child coroutine.
     */
    fun about(id: String?): CoroutineContext = working.asContextElement(id)

    private class Line(val at: Long, val level: Char, val text: String, val track: String?)

    private val lines = ArrayDeque<Line>()

    /** Total characters held, so the buffer is bounded by size rather than by count. */
    private var held = 0

    /**
     * Bounded by bytes rather than by line count: one `callExport result` line
     * carrying a search response is worth several hundred ordinary lines, and a
     * limit that counts them the same either wastes memory or throws away the
     * history that matters.
     */
    private fun record(level: Char, message: String, about: String?) {
        val text = if (message.length > MAX_LINE_CHARS) {
            message.take(MAX_LINE_CHARS) + "…(${message.length - MAX_LINE_CHARS} more)"
        } else {
            message
        }
        synchronized(lines) {
            lines.addLast(Line(System.currentTimeMillis(), level, text, about))
            held += text.length
            while (held > MAX_HELD_CHARS && lines.isNotEmpty()) {
                held -= lines.removeFirst().text.length
            }
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /**
     * Wall-clock times at which each track became the current one.
     *
     * Only a floor for tracks with no lines of their own: a track served whole
     * from the disk cache is resolved by nobody and would otherwise have no
     * start at all.
     */
    private val startedAt = ConcurrentHashMap<String, Long>()

    fun onTrackStarted(videoId: String) {
        if (startedAt.size >= MAX_REMEMBERED) startedAt.clear()
        startedAt[videoId] = System.currentTimeMillis()
    }

    /**
     * The log for [song]: the lines about that track, plus the ones about
     * nothing in particular, from where its own story starts.
     */
    suspend fun forTrack(song: Song): String = withContext(Dispatchers.Default) {
        val held = synchronized(lines) { lines.toList() }
        val from = listOfNotNull(
            held.firstOrNull { it.track == song.videoId }?.at,
            startedAt[song.videoId]?.minus(LEAD_IN_MS),
        ).minOrNull()
        val since = held.filter { from == null || it.at >= from }
        val window = since.filter { it.track == null || it.track == song.videoId }
        header(song, from, window.size, since.size - window.size) + "\n" +
            window.joinToString("\n") { "${CLOCK.format(Date(it.at))} ${it.level} ${it.text}" } +
            "\n"
    }

    // ── The part that isn't the log ─────────────────────────────────────────

    /**
     * What the lines alone can't say: which build produced them, on what.
     *
     * @param elsewhere how many lines in the same stretch belonged to another
     *   track and were left out. Stated rather than silently dropped: it is the
     *   difference between "nothing happened" and "nothing happened *to this
     *   track*", and the two send a reader looking in opposite places.
     */
    private fun header(song: Song, from: Long?, count: Int, elsewhere: Int) = buildString {
        appendLine("BitChord for Windows log — ${song.title} — ${song.artist}")
        appendLine("id=${song.videoId} duration=${song.durationText ?: "?"} album=${song.albumName ?: "?"}")
        appendLine(
            "window: ${from?.let { CLOCK.format(Date(it)) } ?: "everything held"} → " +
                "${CLOCK.format(Date())} ($count lines" +
                (if (elsewhere > 0) ", $elsewhere for other tracks left out)" else ")"),
        )
        appendLine("device: ${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
            "${System.getProperty("os.arch")}, Java ${System.getProperty("java.version")}")
    }

    private val CLOCK = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * How far back of a track's selection to reach when nothing was ever filed
     * against it — see [startedAt].
     */
    private const val LEAD_IN_MS = 20_000L

    /** Roughly the last few tracks' worth, and small enough to hold without thinking about it. */
    private const val MAX_HELD_CHARS = 512_000

    /** Enough for a stack trace or a search response's opening; not a whole catalogue page. */
    private const val MAX_LINE_CHARS = 2_000

    private const val MAX_REMEMBERED = 32
}
