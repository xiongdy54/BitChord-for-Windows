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

    /**
     * `Player.removeMediaItem(index)`.
     *
     * Removing a row behind the playhead must move the playhead down; ExoPlayer does this
     * implicitly, so an implementation that keeps the index where it was leaves the needle on a
     * different track than the one the caller was playing.
     */
    fun removeAt(index: Int)

    /**
     * `Player.replaceMediaItems(fromIndex, toIndex, mediaItems)`.
     *
     * Shrinking a range behind the playhead must move the playhead down by the rows that went away,
     * and growing one must move it up — ExoPlayer does this implicitly. An edit entirely ahead of the
     * playhead leaves it where it is, whatever it does to the row count: that is the shape of the
     * calls this member exists for, since a hand-queued insertion and shuffle's reorder both pass
     * `from = currentIndex + 1`, and the needle has to stay put through them — the app's own
     * count-changing edit ahead of it (`PartySync.kt:898`) rewrites the tail with a smaller list and
     * never re-seeks. Equal-size rewrites, which is all shuffle ever does, sit where the two rules
     * agree — so it is the ahead case, and only that, this clause has to settle.
     */
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
