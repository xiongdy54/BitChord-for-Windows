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

    /**
     * The list or the playhead changed — republish.
     *
     * Fires once per list **edit**, not once per act: an act that deletes rows goes through
     * [removeAt] once per row, and each of those publishes. So one advance over a queue with k
     * consumed USER rows publishes k+1 times — [afterMoved] runs
     * [QueueCoordinator.consumePlayedUserQueue] and then publishes itself — and [clearUserQueue]
     * publishes once per row cleared. `an advance that prunes a consumed user row publishes once per
     * edit` is that path.
     *
     * The count is not something a caller may lean on, and nothing needs it to be: the consumer's
     * `publish()` writes a whole `PlayerState` snapshot into a `MutableStateFlow`
     * (`_state.value = state.copy(...)`), so a repeated publish of an unchanged state costs one
     * structural equality check and nothing else. Do not batch the publications to make the count
     * match an act — that would be a mechanism of its own, and per-edit publication is what the
     * ported coordinator gets for free. The invariant is the list and the needle, not how often the
     * change is announced.
     */
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

    /**
     * Delete a row the queue panel offered to delete.
     *
     * Two refusals, and both of them are this function's business rather than the caller's. The row
     * under the needle is what is playing, and deleting it is not a thing the panel asks for. And an
     * index that is not in the list any more has to be refused too: [removeAt] forwards straight to
     * `items.removeAt`, which throws, and this is the one index entry with no guard of its own while
     * [trimHistory] is shrinking the head of the list from inside the pump. A row drawn at index 26
     * in the panel can therefore be past the end by the time its delete button is clicked — the same
     * shape [moveRow], [jumpTo] and `QueueCoordinator.jumpToQueueItem` each already guard.
     */
    fun removeRow(index: Int) {
        if (index in items.indices && index != currentIndex) removeAt(index)
    }

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
     * End of media, and the only place that decides what plays next *when a track runs out*
     * (spec §4). Repeat-one replays in place; otherwise walk forward; wrap under repeat-all; stop at
     * the tail. [next] and [previous] are the same rules for a button press rather than an engine
     * callback, and every index move in the three of them leaves through [afterMoved], so the history
     * window and the consumed user rows are pruned the same way whichever way the needle moved.
     */
    fun onFinished(): String? {
        if (items.isEmpty()) return null
        if (repeatMode == RepeatMode.ONE) return items[currentIndex].videoId
        if (currentIndex + 1 < items.size) { currentIndex += 1; return afterMoved() }
        if (repeatMode == RepeatMode.ALL) { currentIndex = 0; return afterMoved() }
        return null
    }

    /**
     * The next button. Steps forward, and — under repeat-all only — wraps from the tail to the head
     * exactly as [onFinished] does, so the glyph stays honest in the one mode that loops the whole
     * list.
     *
     * The wrap is what the original gets from ExoPlayer rather than writing out: the transport on
     * Android is a media-session call that hands the press straight to the player
     * (`PlaybackService.kt:6573-6591` → `seekToNextMediaItem()`), and the enabled state the UI dims
     * from is `player.hasNextMediaItem()` (`PlayerConnection.kt:305-306`) — an answer from the player
     * rather than an index comparison, which is why [PlayerState]'s carried KDoc can say the
     * wrap-around of repeat-all is already accounted for. Here the answer has to be computed, and an
     * enabled glyph that returns nothing is the dead button spec §2 rules out.
     *
     * The rule copied is Media3's own `Player.REPEAT_MODE_*` contract: repeat-all gives the "Previous"
     * and "Next" actions "looping at the ends, so that Next when playing the last MediaItem will move
     * to the first", while repeat-one's "behave as they do in REPEAT_MODE_OFF … doing nothing when
     * there is no previous or next MediaItem". So repeat-one gets no wrap — there the loop belongs to
     * the current row, which [onFinished] answers in place above — and at the tail with repeat off
     * this is a stop.
     */
    fun next(): String? {
        if (items.isEmpty()) return null
        if (currentIndex + 1 < items.size) { currentIndex += 1; return afterMoved() }
        if (repeatMode == RepeatMode.ALL) { currentIndex = 0; return afterMoved() }
        return null
    }

    /**
     * The back button: restart the current row if the listener is already into it, otherwise step
     * back — and under repeat-all, wrap to the tail from the head, for the same reason [next] wraps.
     *
     * The restart rule keeps precedence over the wrap: past [BACK_RESTARTS_AFTER_MS] the press is
     * answered by the row already under the needle, whether or not there is anywhere to wrap to. A
     * single-row queue wraps onto itself, which is a replay rather than a throw or a spin.
     */
    fun previous(positionMs: Long): String? {
        if (items.isEmpty()) return null
        if (positionMs > BACK_RESTARTS_AFTER_MS) return items[currentIndex].videoId
        if (currentIndex > 0) { currentIndex -= 1; return afterMoved() }
        if (repeatMode == RepeatMode.ALL) { currentIndex = items.size - 1; return afterMoved() }
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
