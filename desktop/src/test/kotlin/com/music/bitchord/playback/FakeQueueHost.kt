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
        repeat(to - from) { items.removeAt(from) }
        items.addAll(from, songs)
    }
    override fun setTimeline(songs: List<Song>, startIndex: Int) {
        items.clear(); items.addAll(songs); currentIndex = startIndex
    }
    override fun jumpTo(index: Int, positionMs: Long) { currentIndex = index; played += items[index].videoId }
    override fun playCurrent() { items.getOrNull(currentIndex)?.let { played += it.videoId } }

    /** The tier the algorithms ask for at an index. */
    fun tierAt(index: Int): QueueTier = items[index].queueTier
}
