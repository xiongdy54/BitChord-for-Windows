package com.music.bitchord.desktop.playback

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.playback.PlaybackStatus
import com.music.bitchord.playback.PlayerState
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.playback.QueueTimeline
import com.music.bitchord.playback.RepeatMode
import kotlinx.coroutines.CancellationException
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
     * Test seam. Production asks the download record first — a saved file
     * answers before the network is asked, and a record outliving its file is
     * pruned on the way past ([Downloads.verifiedSavedUri]) — then resolves
     * through [StreamResolver], whose cache and coalescing already exist.
     */
    var resolveUrl: suspend (String) -> String = {
        Downloads.verifiedSavedUri(it) ?: StreamResolver.resolve(it)
    }

    /**
     * Handed the videoId of the row after the one that just started, for spec §2 decision 7.
     *
     * Production leaves this null and the prefetch calls [resolveUrl] itself; a test sets it to
     * record that the read-ahead happened.
     */
    var onPrefetch: (suspend (String) -> Unit)? = null

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
        engine.onError = { message ->
            onQueue {
                _status.value = PlaybackStatus.EngineError(message)
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
     *
     * Nothing waits for the fetch it starts, and nothing cancels it either — the row it was asked
     * for can be history before the URL arrives. [playGeneration] is what makes that harmless.
     */
    private fun resolveAndPlay(videoId: String) {
        val generation = ++playGeneration
        engine.stop()
        position.positionMs = 0
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
                        engine.play(
                            url,
                            if (url.startsWith("http")) StreamResolver.mediaHeadersFor(url) else emptyMap(),
                        )
                        engine.setVolume(_volume.value)
                        prefetchNext()
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
    }
}
