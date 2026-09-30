package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song

/**
 * A queue that is only a list. Stands in for the player the way the Proxy fake
 * stood in for Media3's Player in app/src/test/java/com/music/bitchord/QueueCoordinatorTest.kt.
 */
class FakeQueueHost(
    songs: List<Song>,
    override var currentIndex: Int = 0,
) : QueueHost {
    val items: MutableList<Song> = songs.toMutableList()
    val played = mutableListOf<String>()

    /**
     * The two playback entries in the order they were called, with the position [jumpTo] was given.
     *
     * [played] cannot tell them apart — both append the row they land on — while on a real host they
     * are different acts: `jumpTo` is `seekTo(index, positionMs)` + play, so it restarts the clock,
     * and `playCurrent` leaves the position alone and only sounds the row under the needle. A
     * forward jump rebuilds the list and plays; a backward jump seeks into the history already
     * there. Without this channel a test cannot tell the two apart.
     */
    val playbackCalls = mutableListOf<String>()

    override val itemCount: Int get() = items.size
    override fun songAt(index: Int): Song? = items.getOrNull(index)
    override fun removeAt(index: Int) {
        items.removeAt(index)
        // ExoPlayer shifts currentMediaItemIndex when an item behind the playhead
        // is removed; the fake has to, or the mutating functions look correct while
        // the real host would land somewhere else.
        if (index < currentIndex) currentIndex -= 1
    }
    override fun replaceRange(from: Int, to: Int, songs: List<Song>) {
        val replaced = to - from
        repeat(replaced) { items.removeAt(from) }
        items.addAll(from, songs)
        // Same shifting rule as removeAt, and the same guard as the real host's
        // replaceRange in QueueTimeline: only an edit behind the needle moves it.
        // shuffle's reorder is always ahead and always equal-size, so this is a
        // no-op there — but the fake must not be the only place that knows what
        // a real host does.
        if (from < currentIndex) {
            currentIndex = (currentIndex + songs.size - replaced)
                .coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
    }
    override fun setTimeline(songs: List<Song>, startIndex: Int) {
        items.clear(); items.addAll(songs); currentIndex = startIndex
    }
    override fun jumpTo(index: Int, positionMs: Long) {
        playbackCalls += "jumpTo($index, $positionMs)"
        currentIndex = index
        played += items[index].videoId
    }
    override fun playCurrent() {
        playbackCalls += "playCurrent()"
        items.getOrNull(currentIndex)?.let { played += it.videoId }
    }

    var repeatMode: Int = RepeatMode.OFF

    /** PlaybackService.kt:2712-2724: under repeat-all, expired history rotates to the tail. */
    fun trimHistory() {
        val expired = queueHistoryTrimCount(currentIndex)
        if (expired <= 0) return
        if (repeatMode == RepeatMode.ALL) repeat(expired) { items.add(items.removeAt(0)) }
        else repeat(expired) { items.removeAt(0) }
        currentIndex -= expired
    }

    /** The tier the algorithms ask for at an index. */
    fun tierAt(index: Int): QueueTier = items[index].queueTier
}
