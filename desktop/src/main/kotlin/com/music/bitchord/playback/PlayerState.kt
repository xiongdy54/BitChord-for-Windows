// PlayerState, PlaybackPosition and the two pure queue-index helpers, cut verbatim out of
// app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt (:42-94, :485-489, :780-781).
// The Media3 bridge around them does not come over: the desktop player holds Songs in this
// process, so the MediaItem/Bundle serialisation — 16 EXTRA_* keys, toSong/toMediaItem, the
// two Bundle round-trips — has nothing left to be for.
package com.music.bitchord.playback

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.data.model.Song

/** Desktop stand-in for `androidx.media3.common.Player.REPEAT_MODE_*`: three states, same order. */
object RepeatMode {
    const val OFF = 0
    const val ONE = 1
    const val ALL = 2
}

/**
 * What the player is waiting on or stumbled over, in structured form.
 *
 * Slice 1 published this as ready-made English strings, which left the UI two
 * choices — print a foreign sentence, or parse it back apart. The words are the
 * UI's business; the controller only knows *which* thing happened and the
 * label/reason it happened to, so that is all it says.
 */
sealed interface PlaybackStatus {
    /** A stream URL is being resolved for the row under the needle. */
    data object Resolving : PlaybackStatus

    /** A collection was tapped and its tracks are being fetched. */
    data class Opening(val label: String) : PlaybackStatus

    /** The collection opened, but held no playable track. */
    data class NothingPlayable(val label: String) : PlaybackStatus

    /** The stream URL resolve failed — [reason] is the underlying message, when there was one. */
    data class ResolveFailed(val reason: String?) : PlaybackStatus

    /** The engine reported a playback error of its own; the message is the engine's. */
    data class EngineError(val message: String) : PlaybackStatus
}

/**
 * The playhead, deliberately kept out of [PlayerState].
 *
 * It moves twice a second; everything else on [PlayerState] moves on a track
 * change. Carried in the same object, the two are one snapshot read — and
 * `PlayerController` hands its state over as one value (a `StateFlow<PlayerState>`
 * collected where it is shown), which makes that read non-restartable, which
 * pushes it up into its *caller's* scope. In this app the caller is the root of
 * the whole UI, so a ticking playhead invalidated the entire tree twice a
 * second: every tab, the frosted top bar, the floating bar and the mini player,
 * and the real-time blurs underneath them, whether or not anything on screen
 * showed a position.
 *
 * Split out and held behind a stable object, the tick is a read of this alone.
 * Whoever draws a scrubber reads it and recomposes; nobody else hears about it.
 * Take care to keep it that way — reading [positionMs] high in the tree and
 * passing the `Long` down puts the invalidation straight back where it was.
 */
@Stable
class PlaybackPosition internal constructor() {
    var positionMs by mutableLongStateOf(0L)
        internal set
}

/** Snapshot of playback state, driven by `PlayerController`. */
data class PlayerState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    /**
     * The playhead. A field rather than a value: its identity never changes, so
     * carrying it here costs no invalidation — see [PlaybackPosition].
     */
    val position: PlaybackPosition = PlaybackPosition(),
    /**
     * Left here rather than moved alongside the position: it settles once per
     * track, and [mutableStateOf] compares structurally, so the poll writing it
     * back unchanged every tick invalidates nothing.
     */
    val durationMs: Long = 0L,
    val error: String? = null,
    /** True while the player is buffering — including our own stream-URL resolution. */
    val isLoading: Boolean = false,
    val repeatMode: Int = RepeatMode.OFF,
    val queue: List<Song> = emptyList(),
    val queueIndex: Int = 0,
    /**
     * Whether the queue has somewhere to go either side of the current track.
     * Taken from the player rather than [queueIndex], so the wrap-around of
     * repeat-all is already accounted for.
     */
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    /** True while the current item is the replacement installed by a quality upgrade. */
    val isQualityUpgraded: Boolean = false,
)

/**
 * Where AutoPlay's section of the queue begins, and so where a track queued by
 * hand belongs — above the mix, below everything the user picked.
 *
 * Read as "the first of AutoPlay's tracks still to come", which is what keeps
 * it below the playing track even when the mix itself is what's playing: the
 * tracks of it already behind you count as played, and the section starts
 * again below the needle. Tracks put in by hand there — "Play next" while the
 * mix runs — stay above it too, for the same reason.
 *
 * The queue panel draws its AutoPlay heading at this same index.
 */
fun autoplaySectionStart(fromAutoplay: List<Boolean>, currentIndex: Int): Int {
    val after = (currentIndex + 1).coerceIn(0, fromAutoplay.size)
    return (after until fromAutoplay.size).firstOrNull { fromAutoplay[it] }
        ?: fromAutoplay.size
}

/**
 * The selected track is moved to the head when a new queue is shuffled, so
 * playback must begin there rather than at its index in the unshuffled list.
 */
internal fun queueStartIndex(requestedIndex: Int, itemCount: Int, shuffled: Boolean): Int =
    if (shuffled) 0 else requestedIndex.coerceIn(0, itemCount - 1)
