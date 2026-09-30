// Why this file exists on desktop and nowhere on Android.
//
// On Android this state lives inside ExoPlayer. The queue is a MediaItemList the player owns,
// "play the next one" is `seekToNextMediaItem()` inside the library, the history window is a
// pruning rule the service applies to a playlist it is already holding, and the tier metadata
// crosses a process boundary in a Bundle per row. `PlayerConnection` therefore never writes an
// advance: it forwards a media item id and the player walks.
//
// Desktop holds the list in this process, so `QueueCoordinator` and `QueueShuffle` can edit it
// directly — and ExoPlayer's silent half has to be said out loud, because nothing advances
// the queue implicitly any more. This class is that half: the list, the playhead, and the pump.
//
// The split that matters, and the reason the pump has one entry point (spec §4 risk 2): the
// functions here only move indices around a list and return the id of the row now under the
// needle. None of them touches the player, the network or Compose. Resolving that id to a stream
// URL and making sound is `PlayerController`'s job, which is why a double-advance needs two calls
// to `onFinished()` to happen and cannot happen by an advance tucked inside a seek.
//
// "Behind the needle" means the rows it has already passed (lower indices), as it does on
// [QueueHost]; the rows still to come are "after" it.
package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry

/**
 * The queue as a list with a playhead, plus the rules for moving the playhead.
 *
 * [QueueHost] in, [Song] ids out: the ported algorithms edit through the interface members below,
 * and the entry points return the id to play so the caller can go and get its audio.
 */
class QueueTimeline : QueueHost {

    companion object {
        /** Past this point in a track, back restarts it instead of skipping. `PlaybackService.kt:157`. */
        const val BACK_RESTARTS_AFTER_MS = 10_000L
    }

    private val items = mutableListOf<Song>()
    override var currentIndex: Int = 0
        private set
    override val itemCount: Int get() = items.size

    /** `Player.REPEAT_MODE_*`, read by the pump and by [trimHistory]. */
    var repeatMode: Int = RepeatMode.OFF

    /**
     * Make the row sound from a given position — `seekTo(index, positionMs)` + play.
     *
     * Split from [onPlay] because the two are different acts on a real engine: a jump restarts the
     * clock, `playCurrent` leaves it alone. Only [jumpTo] uses both.
     */
    var onSeek: (Song, Long) -> Unit = { _, _ -> }

    /** Start a row playing from the beginning. */
    var onPlay: (Song) -> Unit = {}

    /** The list or the playhead changed — republish. Called once per act, not once per row moved. */
    var onChanged: () -> Unit = {}

    override fun songAt(index: Int): Song? = items.getOrNull(index)

    /**
     * The queue read once, as the list the coordinator's builders count indices against.
     *
     * A member here rather than of [QueueHost]: this object *is* the list, so the copy is one call,
     * while every caller that reaches the queue through the seam has to build it from [songAt] —
     * which is what `QueueShuffle`'s private extension of the same name does.
     */
    fun snapshot(): List<Song> = items.toList()

    override fun removeAt(index: Int) {
        items.removeAt(index)
        if (index < currentIndex) currentIndex -= 1
        onChanged()
    }

    override fun replaceRange(from: Int, to: Int, songs: List<Song>) {
        val replaced = to - from
        repeat(replaced) { items.removeAt(from) }
        items.addAll(from, songs)
        // Only an edit behind the needle moves it. ExoPlayer leaves
        // currentMediaItemIndex alone when items ahead of it change size, and
        // shuffle's reorder is always ahead and always equal-size — so guarding
        // this is what keeps a future unequal-size edit from teleporting the
        // playhead.
        if (from < currentIndex) {
            currentIndex = (currentIndex + songs.size - replaced)
                .coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
        onChanged()
    }

    override fun setTimeline(songs: List<Song>, startIndex: Int) {
        items.clear(); items.addAll(songs)
        currentIndex = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        onChanged()
    }

    /** Jump and make it sound — the seekTo + play (+ prepare) pair from `jumpToQueueItem` folded into one. */
    override fun jumpTo(index: Int, positionMs: Long) {
        val song = items.getOrNull(index) ?: return
        currentIndex = index
        onChanged()
        onSeek(song, positionMs)
        onPlay(song)
    }

    override fun playCurrent() { items.getOrNull(currentIndex)?.let(onPlay) }

    // ---- entry points -------------------------------------------------------------

    /** Put a timeline in and say which row it lands on. The only place a whole queue arrives. */
    fun start(timeline: List<Song>, startIndex: Int): String? {
        if (timeline.isEmpty()) return null
        setTimeline(timeline, startIndex)
        return items[currentIndex].videoId
    }

    /**
     * The desktop form of `MediaController.playSongs` (`PlayerConnection.kt:757-774`),
     * minus the MediaItems. Kept as its own function because the shuffle decision
     * belongs here and nowhere else: a queue started while shuffle is on goes in
     * shuffled, with the picked row moved to the head, rather than being played out
     * of order. `queueStartIndex` is the other half of that and must stay paired
     * with `startingOrder` — one moves the row, the other moves the playhead.
     */
    private fun playSongs(songs: List<Song>, startIndex: Int): String? {
        if (songs.isEmpty()) return null
        val shuffled = QueueShuffle.enabled.value
        val queue = if (shuffled) {
            QueueShuffle.startingOrder(songs, startIndex.coerceIn(songs.indices))
        } else {
            songs
        }
        return start(queue, queueStartIndex(startIndex, queue.size, shuffled))
    }

    fun playFrom(newContextSongs: List<Song>, selectedIndex: Int, source: QueueSource): String? {
        if (newContextSongs.isEmpty()) return null
        val result = QueueCoordinator.buildContextQueue(
            currentTimeline = snapshot(), currentIndex = currentIndex,
            newContextSongs = newContextSongs, selectedIndex = selectedIndex, contextSource = source,
        )
        return playSongs(result.timeline, result.startIndex)
    }

    fun startOneOff(tappedSong: Song, source: QueueSource): String? =
        playSongs(
            QueueCoordinator.buildOneOffQueue(snapshot(), currentIndex, tappedSong, source),
            startIndex = 0,
        )

    fun enqueueNext(song: Song) = insert(song, isNext = true)
    fun enqueueLast(song: Song) = insert(song, isNext = false)

    /**
     * Hand-queued rows join the USER_QUEUE block, which sits in the slots straight after the
     * needle — never at the list tail, and never below AutoPlay. Both halves of that are the
     * coordinator's rule ([QueueCoordinator.findUserQueueInsertionIndex]); this only applies it
     * and marks the tier.
     */
    private fun insert(song: Song, isNext: Boolean) {
        val at = QueueCoordinator.findUserQueueInsertionIndex(snapshot(), currentIndex, isNext)
        items.add(at, song.asQueueEntry(QueueTier.USER_QUEUE))
        onChanged()
    }

    fun toggleShuffle() = QueueShuffle.toggle(this)
    fun clearUserQueue() = QueueCoordinator.clearUserQueue(this, ::tierAt)
    private fun tierAt(index: Int): QueueTier = items.getOrNull(index)?.queueTier ?: QueueTier.CONTEXT

    fun jumpToRow(targetIndex: Int) =
        QueueCoordinator.jumpToQueueItem(this, targetIndex, cachedTimeline = snapshot())

    /** The row under the needle is what is playing; deleting it is not a thing the queue panel asks for. */
    fun removeRow(index: Int) { if (index != currentIndex) removeAt(index) }

    /**
     * Drag a row to a new slot, keeping the needle on the track it was on.
     *
     * [to] is where the moved row ends up — `items.add(to, items.removeAt(from))` removes first, so
     * the row that was at [from] lands at index [to] in the list that results, which is Media3's
     * `moveMediaItem(fromIndex, toIndex)` convention too. The needle therefore moves in two steps,
     * and they cancel out when the whole edit happens behind it: taking [from] away shifts it down
     * one, putting the row back at [to] shifts it up one again. Shifting only for the removal — the
     * shape this function was first written in — leaves the needle one row off, on whatever row the
     * drag pushed past it.
     */
    fun moveRow(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        currentIndex = if (from == currentIndex) {
            to
        } else {
            val afterRemoval = if (from < currentIndex) currentIndex - 1 else currentIndex
            if (to <= afterRemoval) afterRemoval + 1 else afterRemoval
        }
        onChanged()
    }

    // ---- the pump -----------------------------------------------------------------

    /**
     * End of media, and the only place that decides what plays next (spec §4). Repeat-one
     * replays in place; otherwise walk forward; wrap under repeat-all; stop at the tail.
     */
    fun onFinished(): String? {
        if (items.isEmpty()) return null
        if (repeatMode == RepeatMode.ONE) return items[currentIndex].videoId
        if (currentIndex + 1 < items.size) { currentIndex += 1; return afterMoved() }
        if (repeatMode == RepeatMode.ALL) { currentIndex = 0; return afterMoved() }
        return null
    }

    fun next(): String? =
        if (currentIndex + 1 < items.size) { currentIndex += 1; afterMoved() } else null

    fun previous(positionMs: Long): String? {
        if (items.isEmpty()) return null
        if (positionMs > BACK_RESTARTS_AFTER_MS) return items[currentIndex].videoId
        if (currentIndex > 0) { currentIndex -= 1; return afterMoved() }
        return null
    }

    /** What moving forward or back costs the list: prune the expired history, then the consumed user rows. */
    private fun afterMoved(): String {
        trimHistory()
        QueueCoordinator.consumePlayedUserQueue(this, ::tierAt)
        onChanged()
        return items[currentIndex].videoId
    }

    /** `PlaybackService.kt:2712-2724`, verbatim in shape. */
    private fun trimHistory() {
        val expired = queueHistoryTrimCount(currentIndex)
        if (expired <= 0) return
        // Under repeat-all the rows behind the needle are still the queue: the loop reaches them
        // again, so they rotate to the tail rather than leaving. With repeat off they were the last
        // thing the listener could go back to and nothing comes after them, so they go.
        if (repeatMode == RepeatMode.ALL) repeat(expired) { items.add(items.removeAt(0)) }
        else repeat(expired) { items.removeAt(0) }
        currentIndex -= expired
    }
}
