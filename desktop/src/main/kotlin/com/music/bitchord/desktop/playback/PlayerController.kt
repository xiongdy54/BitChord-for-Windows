package com.music.bitchord.desktop.playback

import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.SystemClock
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.MAX_AUTOPLAY_EMPTY_REFRESHES
import com.music.bitchord.playback.AUTOPLAY_EMPTY_REFRESH_DELAY_MS
import com.music.bitchord.playback.MAX_QUEUED_AUTOPLAY
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.playback.PlaybackStatus
import com.music.bitchord.playback.PlayerState
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.playback.QueueTimeline
import com.music.bitchord.playback.RepeatMode
import com.music.bitchord.playback.autoplayQueueNeedsRefresh
import com.music.bitchord.playback.loadAutoplayTracks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Playback as the UI sees it, now that playback is a queue.
 *
 * Two halves meet here. [QueueTimeline] holds the list and the playhead and answers every
 * "what now" with a videoId, touching nothing else; the [AudioEngine] under this class holds a
 * stream URL and makes sound. Neither knows the other exists — this class is the only place
 * they are connected, and the connection is always the same three steps: ask the queue, resolve
 * the id it named, hand the URL to the engine.
 *
 * The names of what it publishes are the original's (`spec §3.1`): [PlayerState] is the state
 * the Android player screen reads, field for field.
 *
 * Threading, which is the part a real engine makes hard:
 *
 * - Every queue mutation, and the [publish] that follows it, runs on [queueDispatcher] — one
 *   thread, by construction. [VlcAudioPlayer]'s callbacks arrive on vlcj's own event dispatcher
 *   while a UI action arrives on the Compose/Swing thread, and [QueueTimeline] is a plain
 *   `mutableListOf` with an index over it; [QueueShuffle] is process-wide state read and written
 *   across several host calls. Funnelling both sources through one dispatcher is what stops a
 *   `finished` landing in the middle of a row click.
 * - The one exception is the tick. [position] exists so that a position update invalidates a
 *   scrubber and nothing else (its KDoc has the whole argument), and a single `Long` write into a
 *   `@Stable` holder is already thread-hopping-free — slice 1 wrote the position off the engine
 *   thread the same way. Sending 5 Hz of ticks through the EDT to reach a value the EDT reads
 *   would buy nothing and put the UI thread in the latency path of the audio clock.
 */
class PlayerController(
    private val scope: CoroutineScope,
    private val engine: AudioEngine = VlcAudioPlayer(),
) {

    private val queue = QueueTimeline()

    /**
     * Which row the audio half is working on, and the only answer to "is this resolve still
     * somebody's?".
     *
     * Bumped on every entry to [resolveAndPlay]. A fetch that finishes while it is still the
     * current one is the row under the needle and gets to sound; a fetch whose token has moved on
     * belongs to a row the playhead has already left, and is discarded — see [resolveAndPlay].
     *
     * A token rather than the in-flight [kotlinx.coroutines.Job], because cancelling that job is
     * not the same act as forgetting its result: the coroutine would still run its failure path,
     * and the whole point here is that a row nobody is on any more has nothing to report.
     *
     * A plain `Int`, not an atomic: read and written only on [queueDispatcher], which is the one
     * thread every [resolveAndPlay] entry happens on.
     */
    private var playGeneration = 0

    /**
     * The dispatcher queue edits and state publication are confined to.
     *
     * `Dispatchers.Main` on desktop, which `kotlinx-coroutines-swing` makes the Swing event
     * loop. Tests set it to `Dispatchers.Unconfined` so a JVM run without a display still
     * executes the posted block inline on the caller's thread.
     */
    var queueDispatcher: CoroutineDispatcher = Dispatchers.Main

    /** Where resolving a stream URL happens: the blocking half of a resolve, and nothing else. */
    var resolveDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Test seam. Production asks, in order:
     *
     *  1. **A WebDAV id** (`webdav:<fileUrl>`) — the file URL itself; the
     *     credential rides along as a header through [mediaHeaders], not
     *     through the shared client's interceptor, because libvlc fetches
     *     media with its own stack.
     *  2. **A local file path** — the local scan's id convention is that the
     *     id *is* the absolute path (upstream's `videoId = the URI string`),
     *     so an id that names an existing file plays straight off disk.
     *  3. **A YouTube id** — the download record first ([Downloads.verifiedSavedUri];
     *     this *is* upstream's localUri-first rule for rows the record backs),
     *     then the stream resolver.
     *
     * The seam stays a function of the id alone — the prefetch resolves ids
     * for rows that are not the current one, so it cannot consult "the row
     * under the needle".
     */
    var resolveUrl: suspend (String) -> String = { videoId ->
        com.music.bitchord.data.webdav.WebDavConfig.fileUrlOf(videoId)
            ?: resolveLocalFile(videoId)
            ?: Downloads.verifiedSavedUri(videoId)
            ?: StreamResolver.resolve(videoId)
    }

    /**
     * Handed the videoId of the row after the one that just started, for spec §2 decision 7.
     *
     * Production leaves this null and the prefetch calls [resolveUrl] itself; a test sets it to
     * record that the read-ahead happened.
     */
    var onPrefetch: (suspend (String) -> Unit)? = null

    /**
     * Where one AutoPlay batch is loaded. Test seam: production calls
     * [loadAutoplayTracks], whose network half answers to nothing a unit test
     * owns; a test injects a fake radio here and drives the top-up state machine
     * against it.
     */
    var loadAutoplay: suspend (List<Song>, Song, Int) -> Result<List<Song>> =
        { existing, seed, limit -> loadAutoplayTracks(existing, seed, limit) { YtMusicRepository.radio(it) } }

    /**
     * What the [quality][AppSettings.audioQuality] ladder would pick for
     * [videoId] under a [maxKbps] ceiling, bypassing the resolver's latency
     * cache — the question the swap asks before touching anything audible.
     *
     * Test seam: production walks [StreamResolver.repickFor], which asks the
     * network; a test injects a table and drives the swap state machine.
     */
    var repickUrl: suspend (String, Int) -> String? = { videoId, maxKbps ->
        StreamResolver.repickFor(videoId, maxKbps).url
    }

    /**
     * The second, silent engine the swap proves a candidate on — upstream's
     * hidden audition player (`buildAuditionPlayer`, `PlaybackService.kt:4308`),
     * desktop form: a throwaway [VlcAudioPlayer], volume zero, released the
     * moment the proof is in. A separate `MediaPlayerFactory` comes and goes
     * with it, which is why swaps are minutes apart at most, not continuous.
     *
     * Test seam: production builds the real thing (VLC required); a test
     * injects a fake and scripts its proof.
     */
    var auditionEngine: () -> AudioEngine = { VlcAudioPlayer() }

    /**
     * The playhead, held apart from [state] on purpose.
     *
     * [PlayerState.position] is this same object, so reading a position means reading this and
     * nothing else recomposes. Three things write it: the engine's tick, a row change re-seating
     * the clock, and a jump naming the position it wants — see the class's note on threading.
     */
    val position = PlaybackPosition()

    /**
     * Seeded with [position] rather than left to [PlayerState]'s default: that default builds a
     * *second* [PlaybackPosition], and everything about this object's identity is the point of it
     * (its own KDoc). Without the seed the first render reads one object and every publish after
     * it another — the trap [PlaybackPosition]'s KDoc warns about, and the reason this property
     * is declared above the flow that carries it.
     */
    private val _state = MutableStateFlow(PlayerState(position = position))

    /** The one snapshot the player screens read. */
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _volume = MutableStateFlow(80)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    /** What went wrong, or what is being waited on — and always the *current* row's, never a
     * superseded one's: see [playGeneration]. Null when there is nothing to report, which is the
     * case both for a row that sounded and for the tail the queue walked out to. The wording is
     * the UI's job — this is the structured fact, a [PlaybackStatus]. */
    private val _status = MutableStateFlow<PlaybackStatus?>(null)
    val status: StateFlow<PlaybackStatus?> = _status.asStateFlow()

    /** Shuffle lives in [QueueShuffle], process-wide, so the UI reads that flow directly. */
    val shuffleEnabled: StateFlow<Boolean> = QueueShuffle.enabled

    /** Everything that touches the queue, on one dispatcher and in the order it was asked for. */
    private fun onQueue(block: suspend CoroutineScope.() -> Unit) =
        scope.launch(queueDispatcher, block = block)

    init {
        queue.onPlay = ::playCurrentRow
        // A jump names the position it wants *before* it names the play, so this is the display
        // clock and nothing more. Handing it to the engine instead would be wasted work twice
        // over: the [resolveAndPlay] that follows on the same act stops the engine and re-seats
        // the clock at zero, and the fraction `seekTo(ms)` computes would be divided by the
        // duration of the row being left behind.
        queue.onSeek = { _, ms -> position.positionMs = ms }
        queue.onChanged = ::publish
        engine.onTime = { ms -> position.positionMs = ms }
        engine.onLength = { ms -> onQueue { _state.update { it.copy(durationMs = ms) } } }
        engine.onPlayingChanged = { playing -> onQueue { setPlaying(playing) } }
        // The decoder's verdict on what it is being fed, once per row. The
        // publish goes through the queue thread like every other state write —
        // vlcj reports from its own event dispatcher — and the videoId it is
        // bound to is read there too, so a late report from a row the needle
        // has left cannot land on the new one.
        engine.onAudioStats = { stats ->
            onQueue {
                val videoId = queue.songAt(queue.currentIndex)?.videoId
                NerdStats.onMeasured(
                    videoId,
                    NerdStats.Snapshot(
                        mimeType = stats.codecName,
                        // A track bitrate the engine could not state falls back
                        // to the resolver's figure: measured over recorded, but
                        // a recorded figure beats an absent one.
                        bitrateKbps = stats.bitrateKbps
                            ?: videoId?.let { NerdStats.pickedKbps(it) },
                        sampleRateHz = stats.sampleRateHz,
                        channels = stats.channels,
                        sourceName = videoId?.let { NerdStats.pickedSource(it) },
                    ),
                )
            }
        }
        engine.onError = { message ->
            onQueue {
                _status.value = PlaybackStatus.EngineError(message)
                setPlaying(false)
            }
        }
        // The advance hangs on this one callback and nowhere else (spec §4 risk 2).
        engine.onFinished = { advance() }
        engine.setVolume(_volume.value)
        // The AutoPlay switch is a single source of truth the queue reacts to,
        // wherever it is flipped from — the settings dialog and the queue panel's
        // own heading both write [AppSettings.autoplay], and this collector owns
        // the queue edits each answer requires (upstream does both halves inside
        // the service's toggle handler). Collected on the queue thread, because
        // everything it does is a queue mutation.
        onQueue {
            AppSettings.autoplay.collect { enabled -> applyAutoplay(enabled) }
        }
        // The audio quality switch, unlike this one, is not a collected flow:
        // its one production writer is the settings dialog, which now goes
        // through [setAudioQuality] — an explicit act the queue thread can own
        // end to end, with none of the subscribe-before-first-value timing a
        // `drop(1)` collector would hang the trigger on.
        // The output device is like the quality switch: one explicit writer
        // ([setAudioOutputDevice]) and a one-time apply of whatever a previous
        // session stored, before the first play can open an aout under the
        // wrong endpoint.
        AppSettings.outputDeviceId.value?.let { engine.setAudioOutputDevice(it) }
        watchSleepTimer()
    }

    // ---- sleep timer -------------------------------------------------------------------

    /**
     * Pause when the sleep timer runs out (`PlaybackService.kt:5096`'s watcher,
     * ported).
     *
     * `collectLatest` is what makes re-setting the timer work: the pending wait
     * is cancelled and restarted on the new deadline instead of both firing.
     * Pausing is not gated on playing — upstream's `player?.pause()` isn't
     * either — because a pause on an idle engine is a no-op and checking would
     * only add a second reading that could disagree with the engine's own.
     */
    private fun watchSleepTimer() {
        scope.launch {
            SleepTimer.deadline.collectLatest { deadline ->
                if (deadline == null) return@collectLatest
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining > 0) delay(remaining)
                TrackLog.d(TAG, "sleep timer elapsed; pausing")
                engine.pause()
                SleepTimer.cancel()
            }
        }
    }

    // ---- audio quality ----------------------------------------------------------------

    /**
     * The audio quality switch, as the player sees it.
     *
     * Writing the setting is the listener's word; [applyQualityChange] is what
     * the word costs the current track and the resolver's cache. One method
     * owns both, so every surface that flips the switch lands in the same
     * place, on the queue thread, whatever wrote it — the same shape
     * [setAutoplay] gives the AutoPlay switch.
     */
    fun setAudioQuality(value: AudioQuality) {
        if (AppSettings.audioQuality.value == value) return
        AppSettings.setAudioQuality(value)
        onQueue { applyQualityChange(value) }
    }

    /**
     * The output device switch, as the player sees it — [setAudioQuality]'s
     * shape with the engine call in place of the swap.
     *
     * [id] is the platform's own endpoint id, or null for "whatever the system
     * default is right now"; [name] rides along so the settings row can still
     * say what was chosen after the device stops enumerating. The engine call
     * is a native libvlc one-liner, thread-safe like every other audio verb
     * this class already makes from the UI thread ([setVolume],
     * [togglePlayPause]) — it does not queue, because it is not a queue edit
     * and has nothing to say to the snapshot.
     */
    fun setAudioOutputDevice(id: String?, name: String?) {
        if (AppSettings.outputDeviceId.value == id && AppSettings.outputDeviceName.value == name) return
        AppSettings.setAudioOutputDevice(id, name)
        engine.setAudioOutputDevice(id)
        TrackLog.d(TAG, "audio output device -> ${name ?: id ?: "system default"}")
    }

    /**
     * The endpoints the engine's platform lists, verbatim — the settings
     * dialog's list. The one engine call that blocks on native enumeration,
     * so the dialog that asks runs it on [Dispatchers.IO], not the UI thread.
     */
    fun audioOutputDevices(): List<AudioOutputDevice> = engine.audioOutputDevices()

    // ---- autoplay -------------------------------------------------------------------

    /**
     * The seed the running top-up was loaded for, and the job loading it.
     *
     * One track transition reports itself to the player several times over, and
     * each report would start a load; the seed latches the first and the rest
     * read it and stop (upstream's `autoplaySeed`, `PlaybackService.kt:2307`).
     * Cleared whenever the answer changes underneath the load — the track moved
     * on, the mode changed, the switch flipped — so the next track can top up.
     */
    private var autoplaySeed: String? = null
    private var autoplayLoadJob: Job? = null

    /**
     * The AutoPlay tail, taken off the queue for the duration of a repeat-all
     * stretch and put back when it ends (`PlaybackService.kt:2428`/`:2449`).
     *
     * Only ever taken once per stretch: cycling OFF → ALL → ONE → OFF sets the
     * mode three times, and the second and third of those must not overwrite a
     * full stash with the empty tail the first one left behind.
     */
    private var repeatAllStash: List<Song> = emptyList()
    private var repeatAllStashSeed: String? = null

    /**
     * The queue edits each position of the AutoPlay switch requires.
     *
     * Off: the switch is the listener saying they don't want those tracks — the
     * unplayed suggestions leave the queue now, and the stash leaves with them,
     * because leaving it behind would put them back when the next repeat-all
     * ended. On: nothing to remove and a top-up to re-arm; the seed clears so a
     * queue that is sitting at its own tail tops up immediately rather than
     * waiting for a track change that may never come.
     */
    private fun applyAutoplay(enabled: Boolean) {
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        if (!enabled) {
            repeatAllStash = emptyList()
            repeatAllStashSeed = null
            queue.dropAutoplayTail()
            return
        }
        maybeTopUpAutoplay()
    }

    /**
     * Fill the AUTOPLAY tier behind the current track while it is the last one
     * playing — the desktop form of `loadAutoplayForCurrentTrack`
     * (`PlaybackService.kt:2274`), keyed off the resolve that starts a track
     * rather than the transition callback the service receives, which here is
     * the same moment with fewer aliases.
     */
    private fun maybeTopUpAutoplay() {
        val current = queue.songAt(queue.currentIndex) ?: return
        if (!autoplayQueueNeedsRefresh(
                enabled = AppSettings.autoplay.value,
                repeatAll = queue.repeatMode == RepeatMode.ALL,
                currentIndex = queue.currentIndex,
                itemCount = queue.itemCount,
                loadInProgress = autoplayLoadJob?.isActive == true,
            )
        ) {
            return
        }
        val needed = MAX_QUEUED_AUTOPLAY - upcomingAutoplayCount()
        if (needed <= 0) return
        if (autoplaySeed == current.videoId) return
        autoplaySeed = current.videoId
        autoplayLoadJob = onQueue {
            var remaining = needed
            var emptyRefreshesRemaining = MAX_AUTOPLAY_EMPTY_REFRESHES
            while (isActive) {
                // The queue may have moved on while the last request was in
                // flight — or the switch or the mode may have flipped. Every
                // pass re-reads all three before spending another one.
                if (!AppSettings.autoplay.value) return@onQueue
                if (queue.repeatMode == RepeatMode.ALL) return@onQueue
                if (queue.songAt(queue.currentIndex)?.videoId != current.videoId) return@onQueue
                val existing = queue.snapshot()
                val result = withContext(resolveDispatcher) { loadAutoplay(existing, current, remaining) }
                val resolved = result.getOrElse { error ->
                    TrackLog.w(TAG, "autoplay top-up failed: ${error.message}")
                    emptyList()
                }
                // And again before writing: the answer belongs to the track that
                // asked for it, or to nobody.
                if (!AppSettings.autoplay.value) return@onQueue
                if (queue.repeatMode == RepeatMode.ALL) return@onQueue
                if (queue.songAt(queue.currentIndex)?.videoId != current.videoId) return@onQueue
                if (resolved.isNotEmpty()) {
                    queue.appendAutoplay(resolved)
                    return@onQueue
                }
                // Empty: while the current item is genuinely the end of the
                // queue, make a delayed fresh request, bounded — the seed stays
                // latched either way, so an unlimited loop would be a poller.
                if (queue.currentIndex != queue.itemCount - 1) return@onQueue
                if (emptyRefreshesRemaining-- <= 0) return@onQueue
                remaining = MAX_QUEUED_AUTOPLAY - upcomingAutoplayCount()
                if (remaining <= 0) return@onQueue
                delay(AUTOPLAY_EMPTY_REFRESH_DELAY_MS)
            }
        }
    }

    /** How many of the rows still to come AutoPlay put there (`PlaybackService.kt:2303`). */
    private fun upcomingAutoplayCount(): Int =
        (queue.currentIndex + 1 until queue.itemCount)
            .count { queue.songAt(it)?.queueTier == QueueTier.AUTOPLAY }

    // ---- quality swaps -----------------------------------------------------------------

    /**
     * The URL the audio half is actually working on — the yardstick the swap
     * measures its candidate against, and the thing that changes when a swap
     * lands. Null while nothing has sounded or the row is still resolving.
     *
     * Read and written only on [queueDispatcher], like every other member of
     * that sentence.
     */
    private var playingUrl: String? = null

    /**
     * The running swap, if any. A second quality change cancels it and starts
     * fresh: the user's latest word is the only one worth serving, and an
     * audition mid-flight is a network walk nobody is waiting for any more.
     * Also cancelled by [resolveAndPlay] — a row change invalidates the whole
     * affair it was in aid of.
     */
    private var qualitySwapJob: Job? = null

    /**
     * What a quality change means for the track playing right now.
     *
     * Upstream, the answer is "nothing" — the setting reaches the next
     * resolve, whenever the queue gets there. The desktop leans on that
     * answer too and it breaks for two reasons this class can see: the
     * setting is one switch here rather than a per-network pair, and
     * [prefetchNext] has often resolved the next track *already*, parking its
     * URL in the resolver's latency cache — so "next track" would not even
     * mean next track until that cache expired. A switch made mid-queue could
     * go unheard for twenty minutes.
     *
     * Hence two acts. [StreamResolver.onQualityChanged] clears the cache, so
     * every future resolve asks the new ceiling. And the current track gets
     * the desktop's own version of the upstream second look: re-pick the
     * ladder under the new ceiling, and if it names a different stream,
     * *prove* that stream on a silent second engine ([auditionProves]) before
     * the playing one is touched. The proof failing — the ordinary case when
     * a rung does not exist or a URL dies — changes nothing audible, which is
     * the whole shape of the guards here: a missed swap is a quieter failure
     * than an interrupted song.
     */
    private fun applyQualityChange(quality: AudioQuality) {
        qualitySwapJob?.cancel()
        StreamResolver.onQualityChanged()
        val song = queue.songAt(queue.currentIndex) ?: return
        val url = playingUrl ?: return
        // Paused is the one state a swap cannot survive: the engine has no
        // open-and-hold, so the swapped-in stream would *play*, un-pausing a
        // track the listener had stopped. The next play or pause boundary
        // re-evaluates nothing — this swap is simply declined.
        if (!engine.isPlaying) return
        val duration = _state.value.durationMs
        if (duration > 0 && duration - engine.timeMs < MIN_SWAP_REMAINING_MS) return
        qualitySwapJob = onQueue {
            TrackLog.d(TAG, "quality swap starts for ${song.videoId} under ${quality.maxKbps}kbps")
            val candidate = try {
                withContext(resolveDispatcher) { repickUrl(song.videoId, quality.maxKbps) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                TrackLog.w(TAG, "quality re-pick failed for ${song.videoId}: ${error.message}")
                null
            } ?: return@onQueue
            // The same URL — most often because MEDIUM, HIGH and LOSSLESS
            // share a ceiling here, or the track has one rung — is no swap at
            // all, however different the setting says it is.
            if (candidate == url) return@onQueue
            if (queue.songAt(queue.currentIndex)?.videoId != song.videoId) return@onQueue
            NerdStats.onQualityRaceStart(song.videoId)
            try {
                val audition = auditionEngine()
                val proved = try {
                    auditionProves(audition, mediaHeaders(candidate), candidate, _state.value.durationMs)
                } finally {
                    audition.release()
                }
                if (!proved) {
                    TrackLog.d(TAG, "quality swap for ${song.videoId} failed its audition; nothing changes")
                    return@onQueue
                }
                // Re-checked with the audition behind it: minutes of network
                // may have passed, and everything this branch touches — the
                // needle, the engine — belongs to the row still under it, still
                // sounding. The queue can have moved on, or the track can have
                // run out while the proof ran; swapping into silence would
                // restart audio the listener had let end, the same sin the
                // paused guard declines at the door.
                if (queue.songAt(queue.currentIndex)?.videoId != song.videoId) return@onQueue
                if (!engine.isPlaying) return@onQueue
                val positionMs = engine.timeMs
                engine.play(candidate, mediaHeaders(candidate), startAtMs = positionMs)
                engine.setVolume(_volume.value)
                playingUrl = candidate
                TrackLog.d(TAG, "quality swap landed for ${song.videoId} at ${positionMs}ms")
            } finally {
                NerdStats.onQualityRaceEnd(song.videoId)
            }
        }
    }

    /**
     * Whether [url] earns the cut a swap costs: opened and decoded on the
     * silent [engine], within [AUDITION_TIMEOUT_MS], and — when the playing
     * track's length is known — no further from it than
     * [QUALITY_SWAP_LENGTH_SLACK_MS]. That length match is what the upstream
     * audition's verdict checked for (`auditionVerdict`, `PlaybackService.kt:4228`):
     * a substitute that would play a different edit of the song must not
     * replace the one being heard.
     *
     * The engine is left running on exit either way — [applyQualityChange]
     * releases it in its own stride, so the proof's cleanup is not split
     * across two functions.
     */
    private suspend fun auditionProves(
        engine: AudioEngine,
        headers: Map<String, String>,
        url: String,
        currentDurationMs: Long,
    ): Boolean {
        val stats = CompletableDeferred<AudioStats?>()
        val length = CompletableDeferred<Long?>()
        engine.onAudioStats = { stats.complete(it) }
        engine.onLength = { length.complete(it) }
        // An engine that cannot open the candidate has said no; both waits
        // are released so the timeout does not hold the answer longer than
        // the failure did.
        engine.onError = {
            stats.complete(null)
            length.complete(null)
        }
        engine.setVolume(0)
        engine.play(url, headers)
        return withTimeoutOrNull(AUDITION_TIMEOUT_MS) {
            coroutineScope {
                val statsWait = async { stats.await() }
                val lengthWait = async { length.await() }
                val measured = statsWait.await()
                val totalMs = lengthWait.await()
                measured != null && totalMs != null &&
                    (currentDurationMs <= 0 || abs(totalMs - currentDurationMs) <= QUALITY_SWAP_LENGTH_SLACK_MS)
            }
        } ?: false
    }

    /**
     * The headers a media fetch for [url] must carry — none for a local file,
     * the WebDAV credential for the configured server's own URLs (the same
     * decision the shared client's interceptor makes, restated here because
     * libvlc never passes through that client), and the resolver's
     * googlevideo set for everything else remote.
     */
    private fun mediaHeaders(url: String): Map<String, String> {
        if (!url.startsWith("http")) return emptyMap()
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        if (host != null && com.music.bitchord.data.webdav.WebDavAuth.shouldAuthorize(host)) {
            return com.music.bitchord.data.webdav.WebDavAuth.authHeader
                ?.takeIf { it.isNotBlank() }
                ?.let { mapOf("Authorization" to it) }
                ?: emptyMap()
        }
        return StreamResolver.mediaHeadersFor(url)
    }

    /**
     * Whether [videoId] names a file on this machine — the local scan's id
     * convention. A YouTube id cannot collide with it (no path separators, no
     * directories), and the file check is what makes a moved or deleted
     * folder fall through to the ordinary resolve rather than dying here.
     *
     * Internal so the resolve-branch table can pin the guard itself without
     * dragging the network branches into the test.
     */
    internal fun resolveLocalFile(videoId: String): String? =
        videoId.takeIf { !it.contains("://") && java.io.File(it).isFile }

    // ---- publishing ---------------------------------------------------------------

    /**
     * Republish the snapshot from the queue's current shape.
     *
     * Runs once per list **edit**, not once per act: an advance that prunes k consumed user rows
     * publishes k+1 times, because [QueueTimeline] forwards each of its `removeAt` calls
     * straight to the list and each one announces itself. So this has to be safe to run twice on
     * unchanged input, and it is — every write here is a whole value into a `MutableStateFlow`,
     * which compares structurally and drops the repeat. Do not batch the publications to make the
     * count match an act, and do not lean on the count anywhere.
     *
     * One form, too: every snapshot write goes through `update {}`, never
     * `_state.value = _state.value.copy(…)`. Both read the current value, but only `update` re-reads
     * it if somebody else won the race, so a future writer added on the second form would drop
     * publishes rather than order them.
     */
    private fun publish() {
        val song = queue.songAt(queue.currentIndex)
        val index = queue.currentIndex
        val wraps = queue.repeatMode == RepeatMode.ALL
        _state.update {
            it.copy(
                song = song,
                // `isPlaying` and `isLoading` are deliberately absent, so a list edit leaves them
                // where the audio half put them: they belong to [setPlaying] and [setLoading],
                // which now that the slice-1 flows are gone write the snapshot itself. Carrying
                // them here meant reading them back off a second store, and that second store is
                // exactly what this publish had to keep in step.
                position = position,
                repeatMode = queue.repeatMode,
                queue = queue.snapshot(),
                queueIndex = index,
                // Read from the queue, never guessed from the index — and only the mode that can
                // wrap may light the glyph. Repeat-one replays the current row instead of wrapping
                // (Media3's own contract: its ends "behave as they do in REPEAT_MODE_OFF"), and
                // QueueTimeline.next() returns null in that case, so `!= RepeatMode.OFF` here would
                // light a transport button that does nothing. Same condition, literally.
                hasPrevious = wraps || index > 0,
                hasNext = wraps || index + 1 < queue.itemCount,
            )
        }
    }

    /**
     * The engine's playing flag, straight into the one snapshot the UI reads.
     *
     * Compared before writing: a `MutableStateFlow` already drops a structurally equal repeat,
     * but this also skips the copy that would build a new snapshot for it.
     */
    private fun setPlaying(value: Boolean) {
        if (_state.value.isPlaying == value) return
        // `update`, like every other snapshot write — see [publish].
        _state.update { it.copy(isPlaying = value) }
        // The one line that answers "did something just stop it". Every transition the
        // engine reports lands here and nowhere else, so this is what the screenshots are
        // read against: closing the player has to leave it silent, and an end of track has
        // to make exactly one pair of them. Only on a real change, so the twice-a-second
        // position tick never writes it.
        TrackLog.d(TAG, "playing=$value ${_state.value.song?.videoId ?: "-"}")
    }

    /**
     * The buffering flag — true from the tap until the first byte is on its way, which is what
     * the mini player's spinner and the player's scrubber read.
     */
    private fun setLoading(value: Boolean) {
        if (_state.value.isLoading == value) return
        _state.update { it.copy(isLoading = value) }
    }

    // ---- starting and switching queues ---------------------------------------------

    /**
     * Start a queue from a list of tracks and play row [index] of it — the desktop form of
     * `MediaController.playSongs` (`PlayerConnection.kt:757-774`). The shuffle decision and the
     * tier bookkeeping are [QueueTimeline]'s; this only says where the tap came from.
     */
    fun playFrom(
        songs: List<Song>,
        index: Int,
        source: PlaybackSourceType,
        sourceTitle: String,
        sourceId: String?,
    ) {
        onQueue {
            val videoId = queue.playFrom(songs, index, QueueSource(sourceTitle, source, sourceId))
            startRow(videoId)
        }
    }

    /** One track, tapped where it stood: the queue becomes it, plus whatever the user hand-queued. */
    fun playOneOff(
        song: Song,
        source: PlaybackSourceType,
        sourceTitle: String,
        sourceId: String?,
    ) {
        onQueue {
            startRow(queue.startOneOff(song, QueueSource(sourceTitle, source, sourceId)))
        }
    }

    /**
     * Slice 1's entry point, and the one the home feed and the search rows still call
     * (`HomePage.kt:61-68`, `SearchPage.kt:60`).
     *
     * What [playOneOff] does, with slice 1's `already playing it` guard in front of it — a double
     * tap on a card is a thing that happens. Both are inside the one posted block so the second
     * tap reads what the first one wrote rather than racing it. Task 12 moves these call sites to
     * [playOneOff] / [playFrom], which get told which page the tap came from instead of guessing
     * from the track's own title.
     */
    fun play(song: Song) {
        onQueue {
            val current = _state.value
            if (current.song?.videoId == song.videoId && (current.isPlaying || current.isLoading)) {
                return@onQueue
            }
            startRow(queue.startOneOff(song, QueueSource(song.title, PlaybackSourceType.QUEUE, null)))
        }
    }

    /**
     * What a collection card stands for: fetch what it holds, then play it **as a queue**.
     *
     * Slice 1 played the first track and threw the rest away, which is the CONTEXT tier's gap —
     * a playlist that ends after one song is not a playlist. The list lands through [playFrom],
     * so the rows after the first are the queue the player screen shows.
     */
    fun playCollection(browseId: String, label: String) {
        onQueue {
            setLoading(true)
            _status.value = PlaybackStatus.Opening(label)
            // Out to IO for the round trip and back to the queue thread for the mutation:
            // `withContext` returns to the context it was called from.
            val fetched = withContext(Dispatchers.IO) { YtMusicRepository.browseSongs(browseId) }
            val songs = fetched.getOrNull()?.songs.orEmpty()
            if (songs.isEmpty()) {
                // Nobody else is going to clear the flag: [playFrom], which normally does it in
                // its own stride, is the branch that is not being taken. Kept inside this branch
                // rather than lifted above it, because on the way out to play a queue a
                // `setLoading(false)` here would only drop the spinner for a frame.
                setLoading(false)
                _status.value = PlaybackStatus.NothingPlayable(label)
                // The empty message is what the user sees; the cause is what makes it recoverable.
                // `getOrNull()` alone erases it, and "nothing playable" is then indistinguishable
                // from "the request failed" — so say which, and put the throwable where the
                // copy-log button can reach it.
                fetched.exceptionOrNull()?.let {
                    TrackLog.w(TAG, "playCollection: $label ($browseId) could not be opened", it)
                }
            } else {
                playFrom(songs, 0, PlaybackSourceType.BROWSE, label, browseId)
            }
        }
    }

    /**
     * What the row menu's "start radio" stands for: the song's watch queue
     * (`radio()` — the same `next()` round trip upstream's Automix feeds from)
     * played **as a queue**, first track first. Deliberately the ordinary
     * [playFrom] semantics and not the queue's AUTOPLAY tier: filling that
     * tier — "when the queue runs out, keep going" — is a later slice's
     * decision, and this is only "play me a station seeded on this song".
     *
     * The queue's source title is the seed song's own title, since that is
     * what the player screen will name as what the queue came from.
     */
    fun playRadio(song: Song) {
        onQueue {
            setLoading(true)
            _status.value = PlaybackStatus.Opening(song.title)
            val fetched = withContext(Dispatchers.IO) { YtMusicRepository.radio(song.videoId) }
            val songs = fetched.getOrNull().orEmpty()
            if (songs.isEmpty()) {
                // Same reasoning as [playCollection]: the flag has no other
                // hand to be cleared by on this branch.
                setLoading(false)
                _status.value = PlaybackStatus.NothingPlayable(song.title)
                fetched.exceptionOrNull()?.let {
                    TrackLog.w(TAG, "playRadio: ${song.title} (${song.videoId}) could not be opened", it)
                }
            } else {
                playFrom(songs, 0, PlaybackSourceType.BROWSE, song.title, song.videoId)
            }
        }
    }

    // ---- transport ------------------------------------------------------------------

    fun togglePlayPause() {
        val current = _state.value
        if (current.song == null) return
        if (current.isPlaying) engine.pause() else engine.resume()
    }

    /** The next button. Whether it wraps is the pump's answer, not this one ([QueueTimeline.next]). */
    fun next() {
        onQueue { startRow(queue.next()) }
    }

    /**
     * The back button, decided against where the audio **actually** is.
     *
     * [AudioEngine.timeMs], not the display position: past
     * [QueueTimeline.BACK_RESTARTS_AFTER_MS] a press means "replay this one", and that threshold
     * is a claim about the media clock. The display position is the UI's own number — it mirrors
     * what the engine reports, is re-seated to zero when a row starts, and is what a scrubber
     * writes while a drag is in progress (see [queue]'s `onSeek`) — so deciding on it would make
     * a transport button answer what the screen is showing rather than where the stream is.
     */
    fun previous() {
        val positionMs = engine.timeMs
        onQueue { startRow(queue.previous(positionMs)) }
    }

    /** A tap on a queue row. Re-seating the list is `jumpToQueueItem`'s rule, not this one's. */
    fun jumpTo(index: Int) {
        onQueue { queue.jumpToRow(index) }
    }

    /** Scrub to a millisecond position — the engine only takes a fraction, so that is what this becomes. */
    fun seekTo(ms: Long) {
        val duration = _state.value.durationMs
        engine.seekTo(if (duration > 0) ms.toFloat() / duration.toFloat() else 0f)
    }

    /** Scrub to a fraction of the media, which is what a drag on the scrubber reports. */
    fun seekToFraction(fraction: Float) {
        engine.seekTo(fraction)
    }

    /** OFF → ALL → OFF, the cycle the original offers (`MainActivity.kt:2083-2087`); repeat-one stays unexercised. */
    fun cycleRepeat() {
        onQueue {
            applyRepeat(if (queue.repeatMode == RepeatMode.OFF) RepeatMode.ALL else RepeatMode.OFF, persist = true)
        }
    }

    /**
     * The mode as it is, for the restore `Main.kt` does after this object exists.
     *
     * Deliberately does not persist: writing the restored value back would freeze the default in
     * place and erase "the user never chose one".
     */
    fun setRepeat(mode: Int) {
        onQueue { applyRepeat(mode, persist = false) }
    }

    /**
     * Already on the queue thread — which is where the mode has to be *read* too, so two taps in
     * a row cannot both see the same old one and skip a state.
     */
    private fun applyRepeat(mode: Int, persist: Boolean) {
        val wasAll = queue.repeatMode == RepeatMode.ALL
        val isAll = mode == RepeatMode.ALL
        queue.repeatMode = mode
        if (persist) AppSettings.setRepeatMode(mode)
        // The AutoPlay tail follows the mode: repeat-all would come round to the
        // suggestions again and again, so they step aside for the stretch and
        // return when it ends — each direction refusing where the queue has
        // moved on underneath it.
        if (isAll && !wasAll) stashAutoplayTracks()
        if (wasAll && !isAll) restoreAutoplayTracks()
        // A mode change is not a list edit, so nothing announces it — and the transport's
        // enabled state depends on it, so say so here.
        publish()
    }

    /**
     * Clears the queue's AutoPlay tail for the duration of repeat-all, keeping it to put back.
     *
     * `PlaybackService.kt:2428`'s shape: the tail is taken through
     * [com.music.bitchord.playback.QueueTimeline.dropAutoplayTail], and the seed the stash
     * was taken behind is remembered — the restore is refused when the loop has played on
     * past that track.
     */
    private fun stashAutoplayTracks() {
        if (repeatAllStash.isNotEmpty()) return
        val dropped = queue.dropAutoplayTail()
        if (dropped.isEmpty()) return
        repeatAllStash = dropped
        repeatAllStashSeed = queue.songAt(queue.currentIndex)?.videoId
    }

    /**
     * Puts the stashed AutoPlay tracks back when repeat-all ends.
     *
     * Refused, rather than forced, in the cases where the stash no longer
     * describes the queue: AutoPlay switched off while the loop ran, or the loop
     * played on past the track the stash was taken behind. Both leave
     * [maybeTopUpAutoplay] to fill the queue the ordinary way. A track the
     * listener queued by hand during the loop is not queued twice for having
     * been in the mix before it.
     */
    private fun restoreAutoplayTracks() {
        val stashed = repeatAllStash
        val seed = repeatAllStashSeed
        repeatAllStash = emptyList()
        repeatAllStashSeed = null
        // The seed is stale now either way, so the queue can be topped up again
        // for this track — without this the latch in [maybeTopUpAutoplay] reads a
        // track it has already loaded for and returns, which is how a queue whose
        // stash was refused ended up with nothing after it at all.
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        if (stashed.isEmpty() || !AppSettings.autoplay.value) return
        if (queue.songAt(queue.currentIndex)?.videoId != seed) return
        val present = queue.snapshot().mapTo(mutableSetOf()) { it.videoId }
        val restored = stashed.filter { it.videoId !in present }
        if (restored.isEmpty()) return
        queue.appendAutoplay(restored)
        maybeTopUpAutoplay()
    }

    /** Shuffle as an edit to the queue rather than a playback mode — see [QueueShuffle]. */
    fun toggleShuffle() {
        onQueue { queue.toggleShuffle() }
    }

    // ---- queue edits ----------------------------------------------------------------

    fun enqueueNext(song: Song) {
        onQueue { queue.enqueueNext(song) }
    }

    fun enqueueLast(song: Song) {
        onQueue { queue.enqueueLast(song) }
    }

    fun removeFromQueue(index: Int) {
        onQueue { queue.removeRow(index) }
    }

    fun moveInQueue(from: Int, to: Int) {
        onQueue { queue.moveRow(from, to) }
    }

    /** The hand-queued rows only; the context and AutoPlay sections are not the user's to clear. */
    fun clearQueue() {
        onQueue { queue.clearUserQueue() }
    }

    /**
     * The AutoPlay switch itself.
     *
     * Writing [AppSettings.autoplay] is the whole act: the init block's collector
     * owns the queue edits each answer requires, so every surface that flips the
     * switch — the queue panel's heading, the settings row — lands in the same
     * place, on the queue thread, whatever wrote it.
     */
    fun setAutoplay(enabled: Boolean) {
        AppSettings.setAutoplay(enabled)
    }

    // ---- the audio half ---------------------------------------------------------------

    /** The row under the needle. The queue calls this after any act that re-seated the playhead. */
    private fun playCurrentRow(song: Song) {
        resolveAndPlay(song.videoId)
    }

    /** A videoId the pump handed back: get it sounding, or — null — stop where we are. */
    private fun startRow(videoId: String?) {
        if (videoId != null) resolveAndPlay(videoId)
    }

    /**
     * End of media, and the only thing that advances on its own (spec §4 risk 2).
     *
     * Two acts in this order: [QueueTimeline.onFinished] moves the needle and returns the id now
     * under it, touching nothing but the list; only then does that id get its audio. Running them
     * the other way round would let a failed resolve move the playhead. `null` means the tail with
     * nothing to wrap to — a normal ending, so no [status] message, and the engine has already
     * reported the stop itself.
     *
     * The "sleep after this song" branch sits ahead of both, because both are
     * the queue moving on and the timer's whole meaning is that nothing does
     * (`PlaybackService.kt:2838`, where the transition callback pauses beside
     * the advance it cannot refuse). Declining the advance leaves the finished
     * row under the needle — upstream's player slides the *next* row in paused,
     * because Media3's transition had already happened by the time the hook
     * ran; here the needle has not moved yet, and "this was the last thing that
     * played" is the more honest place to leave it. Repeat-one counts too, or
     * the timer would never fire with repeat-one on — upstream's comment, and
     * the same holds: this branch is ahead of the replay `onFinished` would
     * have ordered.
     */
    private fun advance() {
        onQueue {
            if (SleepTimer.afterTrack.value) {
                TrackLog.d(TAG, "sleep timer: after this song; holding at the finished row")
                SleepTimer.cancel()
                setLoading(false)
                return@onQueue
            }
            val videoId = queue.onFinished()
            if (videoId == null) {
                // The queue ran dry — no transition will ever close the last
                // track out, so the timer is spent whether or not it was a
                // deadline (upstream cancels on STATE_ENDED for the same
                // reason).
                SleepTimer.cancel()
                setLoading(false)
                return@onQueue
            }
            resolveAndPlay(videoId)
        }
    }

    /**
     * Resolve one row and put it on the engine — the body slice 1's `play(song)` was, now keyed
     * by videoId, which is all the queue ever hands back.
     *
     * The state writes happen on the queue thread; only the blocking resolve hops, and it hops
     * back before touching the engine. So the list is already moved by the time a URL is being
     * fetched, and the fetch cannot reorder the queue.
     *
     * Nothing waits for the fetch it starts, and nothing cancels it either — the row it was asked
     * for can be history before the URL arrives. [playGeneration] is what makes that harmless.
     */
    private fun resolveAndPlay(videoId: String) {
        val generation = ++playGeneration
        engine.stop()
        position.positionMs = 0
        // A swap in flight was for the row being left; nothing it learns
        // applies to the one starting. The URL is leaving with it too.
        qualitySwapJob?.cancel()
        playingUrl = null
        // The old row's measured figures leave with it: a badge that still said
        // the previous track's codec while this one opens would be exactly the
        // lie upstream nulled its snapshot across transitions to stop.
        NerdStats.clear()
        // Whatever length the snapshot still carries belongs to the row that just ended.
        _state.update { it.copy(durationMs = 0L) }
        _status.value = PlaybackStatus.Resolving
        setLoading(true)
        scope.launch {
            // Not `runCatching`: that would swallow a [CancellationException] and hand it to the
            // failure branch below, which would report a resolve nobody wants any more as
            // "resolve failed", over the message of the row that replaced it. Cancellation is a
            // change of mind, not an error, and it has to leave this function unreported.
            val outcome = try {
                Result.success(withContext(resolveDispatcher) { resolveUrl(videoId) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
            // The publish inside this block reads the queue, so it belongs on the queue thread —
            // and on the one that may not be edited from here either.
            withContext(queueDispatcher) {
                // Superseded while the URL was being fetched: the row this resolve was started for
                // is no longer the one under the needle, so everything this branch would write —
                // the URL, the cleared status, the cleared spinner — belongs to a row the user has
                // already left. Discard it all; the current row reports for itself.
                if (generation != playGeneration) return@withContext
                outcome
                    .onSuccess { url ->
                        _status.value = null
                        setLoading(false)
                        // A downloaded file needs no headers and would have
                        // them asked of a URL that is not one.
                        engine.play(url, mediaHeaders(url))
                        engine.setVolume(_volume.value)
                        playingUrl = url
                        prefetchNext()
                        maybeTopUpAutoplay()
                    }
                    .onFailure { error ->
                        setLoading(false)
                        _status.value = PlaybackStatus.ResolveFailed(error.message)
                    }
            }
        }
    }

    /**
     * Ask for the next row's stream URL while this one is still playing, so the following
     * advance finds a finished resolve rather than starting one (spec §2 decision 7).
     *
     * No cache of its own: [StreamResolver] already keys by videoId for twenty minutes
     * (`StreamResolver.kt:1082-1084`) and coalesces concurrent resolves of the same track
     * (`:386-416`). The only thing missing was somebody calling it early.
     *
     * Launched and not awaited: awaited, this would hold the row's own resolve coroutine open for
     * a second full round trip, and in production that round trip belongs on the IO pool rather
     * than on the thread that just agreed to make sound. The queue is therefore read *here*, on
     * the queue thread, before the launch — the launched half touches no state at all.
     *
     * A failure is swallowed whole — a prefetch that did not work is worth exactly what it costs,
     * and the real resolve will report it if it matters. Unlike [resolveAndPlay], `runCatching` is
     * the right shape here, cancellation included: this writes no status and no flag, so there is
     * nothing for a stale report to be stale *about*.
     */
    private fun prefetchNext() {
        val next = queue.songAt(queue.currentIndex + 1) ?: return
        val callback = onPrefetch
        scope.launch {
            runCatching {
                withContext(resolveDispatcher) {
                    if (callback != null) callback(next.videoId) else resolveUrl(next.videoId)
                }
            }
        }
    }

    fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _volume.value = clamped
        engine.setVolume(clamped)
    }

    fun release() {
        engine.release()
    }

    private companion object {
        /** The app's one log tag — see [TrackLog]. */
        const val TAG = "BitChord"

        /**
         * Below this much remaining, a swap's cut costs more than the rung it
         * would buy — the tail is nearly spent either way.
         */
        const val MIN_SWAP_REMAINING_MS = 10_000L

        /**
         * The audition's leash. A healthy candidate opens in well under a
         * second and reports its length with the first ticks; eight seconds
         * covers a shaped connection without making the listener wait on a
         * corpse.
         */
        const val AUDITION_TIMEOUT_MS = 8_000L

        /**
         * How far an audition's length may sit from the playing track's and
         * still be the same recording — the upstream verdict's own tolerance
         * (`upgradeLengthSlackMs`), sized to forgive container rounding
         * without forgiving a different edit.
         */
        const val QUALITY_SWAP_LENGTH_SLACK_MS = 2_000L
    }
}
