package com.music.bitchord.desktop.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.playback.PlayerState
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.playback.QueueTimeline
import com.music.bitchord.playback.RepeatMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
     * The dispatcher queue edits and state publication are confined to.
     *
     * `Dispatchers.Main` on desktop, which `kotlinx-coroutines-swing` makes the Swing event
     * loop. Tests set it to `Dispatchers.Unconfined` so a JVM run without a display still
     * executes the posted block inline on the caller's thread.
     */
    var queueDispatcher: CoroutineDispatcher = Dispatchers.Main

    /** Where resolving a stream URL happens: the blocking half of a resolve, and nothing else. */
    var resolveDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Test seam. Production resolves through [StreamResolver], whose cache and coalescing already exist. */
    var resolveUrl: suspend (String) -> String = { StreamResolver.resolve(it) }

    /**
     * Handed the videoId of the row after the one that just started, for spec §2 decision 7.
     *
     * Production leaves this null and the prefetch calls [resolveUrl] itself; a test sets it to
     * record that the read-ahead happened.
     */
    var onPrefetch: (suspend (String) -> Unit)? = null

    private val _state = MutableStateFlow(PlayerState())

    /** The one snapshot the player screens read. */
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /**
     * The playhead, held apart from [state] on purpose.
     *
     * [PlayerState.position] is this same object, so reading a position means reading this and
     * nothing else recomposes. Only two things write it: the engine's tick, and a row change
     * re-seating the clock — see the class's note on threading.
     */
    val position = PlaybackPosition()

    private val _volume = MutableStateFlow(80)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    /** Null when there is nothing to report — a resolve in flight or a finished queue. */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Shuffle lives in [QueueShuffle], process-wide, so the UI reads that flow directly. */
    val shuffleEnabled: StateFlow<Boolean> = QueueShuffle.enabled

    // Three slice-1 flows the chrome of *this* build still reads — Shell.kt:78-81,
    // HomePage.kt:43, SearchPage.kt:51 — kept because the screens that read `state` instead
    // are Tasks 10 and 11. [publish] is their only writer besides the two setters below, so
    // they cannot drift from `state`; the moment that chrome lands, all three come out.
    private val _current = MutableStateFlow<Song?>(null)
    val current: StateFlow<Song?> = _current.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** True from the tap until the first byte is on its way — what the mini player's spinner reads. */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Everything that touches the queue, on one dispatcher and in the order it was asked for. */
    private fun onQueue(block: suspend CoroutineScope.() -> Unit) =
        scope.launch(queueDispatcher, block = block)

    init {
        queue.onPlay = ::playCurrentRow
        queue.onSeek = { _, ms -> seekTo(ms) }
        queue.onChanged = ::publish
        engine.onTime = { ms -> position.positionMs = ms }
        engine.onLength = { ms -> onQueue { _state.update { it.copy(durationMs = ms) } } }
        engine.onPlayingChanged = { playing -> onQueue { setPlaying(playing) } }
        engine.onError = { message ->
            onQueue {
                _status.value = message
                setPlaying(false)
            }
        }
        // The advance hangs on this one callback and nowhere else (spec §4 risk 2).
        engine.onFinished = { advance() }
        engine.setVolume(_volume.value)
    }

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
     */
    private fun publish() {
        val song = queue.songAt(queue.currentIndex)
        val index = queue.currentIndex
        val wraps = queue.repeatMode == RepeatMode.ALL
        _state.value = _state.value.copy(
            song = song,
            isPlaying = _playing.value,
            position = position,
            isLoading = _loading.value,
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
        _current.value = song
    }

    /**
     * The engine's playing flag.
     *
     * `isPlaying` is a snapshot field and a slice-1 flow, so both get it from here; one writer
     * is what keeps the transitional pair in step.
     */
    private fun setPlaying(value: Boolean) {
        if (_playing.value == value) return
        _playing.value = value
        publish()
    }

    /** The buffering flag, same pair. */
    private fun setLoading(value: Boolean) {
        if (_loading.value == value) return
        _loading.value = value
        publish()
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
            if (_current.value?.videoId == song.videoId && (_playing.value || _loading.value)) return@onQueue
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
            _status.value = "opening $label…"
            // Out to IO for the round trip and back to the queue thread for the mutation:
            // `withContext` returns to the context it was called from.
            val songs = withContext(Dispatchers.IO) {
                YtMusicRepository.browseSongs(browseId).getOrNull()?.songs.orEmpty()
            }
            setLoading(false)
            if (songs.isEmpty()) {
                _status.value = "nothing playable in $label"
            } else {
                playFrom(songs, 0, PlaybackSourceType.BROWSE, label, browseId)
            }
        }
    }

    // ---- transport ------------------------------------------------------------------

    fun togglePlayPause() {
        if (_current.value == null) return
        if (_playing.value) engine.pause() else engine.resume()
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
     * is a claim about the media clock. The display position is the UI's own number — written by
     * ticks, re-seated to zero when a row starts, and held by a scrubber mid-drag — so deciding
     * on it would make a transport button answer what the screen is showing.
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
        queue.repeatMode = mode
        if (persist) AppSettings.setRepeatMode(mode)
        // A mode change is not a list edit, so nothing announces it — and the transport's
        // enabled state depends on it, so say so here.
        publish()
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
     */
    private fun advance() {
        onQueue {
            val videoId = queue.onFinished()
            if (videoId == null) {
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
     */
    private fun resolveAndPlay(videoId: String) {
        engine.stop()
        position.positionMs = 0
        // Whatever length the snapshot still carries belongs to the row that just ended.
        _state.update { it.copy(durationMs = 0L) }
        _status.value = "resolving…"
        setLoading(true)
        scope.launch {
            // `runCatching`, not the engine's own error path, because a resolve that throws is
            // this app's failure rather than libvlc's, and it has to be named as one.
            val outcome = withContext(resolveDispatcher) { runCatching { resolveUrl(videoId) } }
            // The publish inside this block reads the queue, so it belongs on the queue thread —
            // and on the one that may not be edited from here either.
            withContext(queueDispatcher) {
                outcome
                    .onSuccess { url ->
                        _status.value = null
                        setLoading(false)
                        engine.play(url, StreamResolver.mediaHeadersFor(url))
                        engine.setVolume(_volume.value)
                        prefetchNext()
                    }
                    .onFailure { error ->
                        setLoading(false)
                        _status.value = "resolve failed: ${error.message ?: error.javaClass.simpleName}"
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
     * (`:386-416`). The only thing missing was somebody calling it early. A failure is swallowed
     * whole — a prefetch that did not work is worth exactly what it costs, and the real resolve
     * will report it if it matters.
     */
    private suspend fun prefetchNext() {
        val next = queue.songAt(queue.currentIndex + 1) ?: return
        val callback = onPrefetch
        runCatching {
            if (callback != null) callback(next.videoId) else resolveUrl(next.videoId)
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
}
