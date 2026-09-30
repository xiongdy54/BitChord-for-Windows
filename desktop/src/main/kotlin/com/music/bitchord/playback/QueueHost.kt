// The seam the ported queue algorithms use instead of Media3's Player.
//
// app/.../playback/QueueCoordinator.kt touches nine Player members: currentMediaItemIndex,
// mediaItemCount, getMediaItemAt (twice), currentMediaItem, removeMediaItem, setMediaItems,
// seekTo+play, and playbackState+prepare. On Android nine because the items have to cross a
// process boundary — hence MediaItem, Bundle and the 16 EXTRA_* keys. Desktop holds Songs in this
// process, so all of that half of the surface disappears: seven members and no serialisation
// ([replaceRange] is an eighth, for the queue edits the coordinator delegates to later tasks).
package com.music.bitchord.playback

import com.music.bitchord.data.model.Song

/**
 * The part of a player the queue algorithms actually use: a list of [Song]s with a playhead.
 *
 * Each member names the Media3 call it replaces, so the two can be read against each other.
 * [replaceRange] is the one the coordinator below never touches: it is the rewrite that shuffle
 * (Task 4) and the hand-queued insertions (Task 3) are built on.
 */
interface QueueHost {
    /** `Player.mediaItemCount`. */
    val itemCount: Int

    /** `Player.currentMediaItemIndex`. */
    val currentIndex: Int

    /** `Player.getMediaItemAt(index).toSong()`, and `Player.currentMediaItem` for the playhead. */
    fun songAt(index: Int): Song?

    /** `Player.removeMediaItem(index)`. */
    fun removeAt(index: Int)

    /** `Player.replaceMediaItems(fromIndex, toIndex, mediaItems)`. */
    fun replaceRange(from: Int, to: Int, songs: List<Song>)

    /** `Player.setMediaItems(items, startIndex, positionMs)`. */
    fun setTimeline(songs: List<Song>, startIndex: Int)

    /**
     * `Player.seekTo(index, positionMs)` followed by `Player.play()` — one call, because on the
     * desktop host seeking and sounding are the same act: the queue is a list, and moving the
     * playhead onto a row is what makes that row play.
     */
    fun jumpTo(index: Int, positionMs: Long = 0L)

    /**
     * `Player.prepare()` when idle, then `Player.play()`. A Media3 player can be sitting in its
     * idle state and go silent without the explicit prepare; the desktop player is not a state
     * machine that needs arming, so this is just "play the row under the needle".
     */
    fun playCurrent()
}
