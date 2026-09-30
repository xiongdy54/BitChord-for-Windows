// Ported from app/src/main/java/com/music/bitchord/playback/QueueCoordinator.kt. The pure builders
// are verbatim; the three functions that mutate a player speak QueueHost where they used to speak
// Media3's Player; buildPartyPlaybackQueue stays on Android, Listen Together being slice 6.
package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import java.util.UUID

// The tier of the queue row at an index. The two pruning functions below and the jump take it as a
// parameter instead of reading it off the host, and so have no default: QueueHost hands out Songs,
// and which row sits where is the caller's business. On Android this was a private
// `Player.queueTierAt`, defaulted to `getMediaItemAt(index).queueTier` — which the JVM tests could
// not use anyway, a MediaItem's metadata bundle being a stub that stores nothing.

/**
 * Information about where a queue or track was started from in the UI.
 */
data class QueueSource(
    val title: String,
    val type: PlaybackSourceType,
    val id: String? = null,
)

/**
 * Result of constructing a context queue, containing the reconstructed timeline
 * and the 0-based start index representing the user's tapped track.
 */
data class ContextQueueResult(
    val timeline: List<Song>,
    val startIndex: Int,
)

/**
 * Headless orchestrator for two-tier Spotify-style queue operations.
 *
 * Owns timeline construction, tier assignment, deterministic queue identity assignment,
 * and user-queue pruning invariants.
 */
object QueueCoordinator {

    /**
     * Converts a [Song] into a queue entry belonging to [tier].
     *
     * Invariant: [Song.queueEntryId] is assigned ONCE when entering the queue and is strictly
     * immutable across all transformations, upgrades, and round-trips.
     */
    fun Song.asQueueEntry(tier: QueueTier): Song = copy(
        queueTier = tier,
        queueEntryId = queueEntryId ?: UUID.randomUUID().toString(),
    )

    /**
     * Constructs an interleaved queue for starting a Context (Album, Playlist, Artist):
     *
     * Invariant: [Preceding Context] + [Selected Track] + [Preserved USER_QUEUE] + [Following Context Tracks].
     * Playback starts at [startIndex] (= precedingContext.size). Preceding tracks remain in history
     * for backward navigation and loop around under REPEAT_MODE_ALL.
     */
    fun buildContextQueue(
        currentTimeline: List<Song>,
        currentIndex: Int,
        newContextSongs: List<Song>,
        selectedIndex: Int,
        contextSource: QueueSource,
    ): ContextQueueResult {
        if (newContextSongs.isEmpty()) return ContextQueueResult(emptyList(), 0)

        val upcomingUserQueue = if (currentIndex in currentTimeline.indices) {
            currentTimeline.subList(currentIndex + 1, currentTimeline.size)
                .filter { it.queueTier == QueueTier.USER_QUEUE }
        } else {
            emptyList()
        }

        val contextEntries = newContextSongs.map { song ->
            song.copy(
                playbackSource = contextSource.title,
                playbackSourceType = contextSource.type,
                playbackSourceId = contextSource.id,
            ).asQueueEntry(QueueTier.CONTEXT)
        }

        val safeIndex = selectedIndex.coerceIn(contextEntries.indices)
        val precedingContext = contextEntries.subList(0, safeIndex)
        val selected = contextEntries[safeIndex]
        val followingContext = contextEntries.subList(safeIndex + 1, contextEntries.size)

        val timeline = precedingContext + listOf(selected) + upcomingUserQueue + followingContext
        val startIndex = precedingContext.size

        return ContextQueueResult(
            timeline = timeline,
            startIndex = startIndex,
        )
    }

    /**
     * Constructs a queue for playing a one-off song (from Search, Home, Explore):
     *
     * Invariant: [Tapped Track] + [Preserved USER_QUEUE].
     */
    fun buildOneOffQueue(
        currentTimeline: List<Song>,
        currentIndex: Int,
        tappedSong: Song,
        source: QueueSource,
    ): List<Song> {
        val upcomingUserQueue = if (currentIndex in currentTimeline.indices) {
            currentTimeline.subList(currentIndex + 1, currentTimeline.size)
                .filter { it.queueTier == QueueTier.USER_QUEUE }
        } else {
            emptyList()
        }

        val oneOffEntry = tappedSong.copy(
            playbackSource = source.title,
            playbackSourceType = source.type,
            playbackSourceId = source.id,
        ).asQueueEntry(QueueTier.CONTEXT)

        return listOf(oneOffEntry) + upcomingUserQueue
    }

    /**
     * Finds the insertion index for user-queued tracks.
     *
     * Invariant:
     * - "Play Next" (isNext = true) inserts at the head of USER_QUEUE (immediately after currentIndex).
     * - "Add to Queue" (isNext = false) inserts at the tail of USER_QUEUE (ahead of CONTEXT and AUTOPLAY).
     */
    fun findUserQueueInsertionIndex(
        timeline: List<Song>,
        currentIndex: Int,
        isNext: Boolean,
    ): Int {
        if (timeline.isEmpty()) return 0
        if (isNext) {
            return (currentIndex + 1).coerceIn(0, timeline.size)
        }

        val start = (currentIndex + 1).coerceIn(0, timeline.size)
        for (i in start until timeline.size) {
            if (timeline[i].queueTier != QueueTier.USER_QUEUE) {
                return i
            }
        }
        return timeline.size
    }

    /**
     * Clears only the upcoming USER_QUEUE items from the player's timeline.
     *
     * Invariant: CONTEXT and AUTOPLAY tracks are completely untouched.
     */
    fun clearUserQueue(host: QueueHost, tierAt: (Int) -> QueueTier) {
        val currentIndex = host.currentIndex
        val count = host.itemCount
        if (count == 0) return

        val userQueueIndices = mutableListOf<Int>()
        for (i in (currentIndex + 1) until count) {
            if (tierAt(i) == QueueTier.USER_QUEUE) {
                userQueueIndices.add(i)
            }
        }

        // Remove in reverse order to preserve preceding indices during removal
        for (i in userQueueIndices.asReversed()) {
            host.removeAt(i)
        }
    }

    /**
     * Prunes played USER_QUEUE items once playback has transitioned into CONTEXT.
     *
     * Invariant: Once an item leaves the USER_QUEUE block and enters CONTEXT, all preceding
     * consumed USER_QUEUE entries are safely removed so that Media3's native REPEAT_MODE_ALL
     * will only cycle through CONTEXT items.
     */
    fun consumePlayedUserQueue(host: QueueHost, tierAt: (Int) -> QueueTier) {
        val currentIndex = host.currentIndex
        if (currentIndex <= 0) return

        if (host.songAt(currentIndex) == null) return
        if (tierAt(currentIndex) != QueueTier.CONTEXT) return

        val playedIndices = mutableListOf<Int>()
        for (i in 0 until currentIndex) {
            if (tierAt(i) == QueueTier.USER_QUEUE) {
                playedIndices.add(i)
            }
        }

        for (i in playedIndices.asReversed()) {
            host.removeAt(i)
        }
    }

    /**
     * Reconstructs the upcoming queue when a listener taps an item in the queue drawer.
     *
     * Invariants:
     * 1. If [targetIndex] <= [currentIndex] or either index is out of bounds, returns `null`
     *    (indicating a backward jump or active track tap handled via standard player seek).
     * 2. [QueueTier.AUTOPLAY]: The tapped track becomes the active track promoted to [QueueTier.CONTEXT]
     *    (starting a fresh radio/station). All future [QueueTier.USER_QUEUE] items across the entire
     *    timeline are preserved immediately after it, then the autoplay items after it. Old context and
     *    bypassed autoplay items are discarded.
     * 3. [QueueTier.CONTEXT]: The tapped track becomes active. All future [QueueTier.USER_QUEUE] items
     *    across the entire timeline are preserved immediately after it. Context items strictly after
     *    [targetIndex] follow after the user queue, then all autoplay items. Bypassed context items are
     *    discarded.
     * 4. [QueueTier.USER_QUEUE]: Preceding user queue items between [currentIndex] + 1 and [targetIndex]
     *    were bypassed within the manual queue and are consumed. Subsequent user queue items, along with
     *    all future context and autoplay tracks, are preserved.
     */
    fun buildJumpQueue(
        currentTimeline: List<Song>,
        currentIndex: Int,
        targetIndex: Int,
    ): List<Song>? {
        if (currentIndex !in currentTimeline.indices || targetIndex !in currentTimeline.indices) {
            return null
        }
        if (targetIndex <= currentIndex) {
            return null
        }

        val targetSong = currentTimeline[targetIndex]
        val allFutureUserQueue = currentTimeline.subList(currentIndex + 1, currentTimeline.size)
            .filter { it.queueTier == QueueTier.USER_QUEUE }
        // Only what the jump skipped over is dropped; AutoPlay lined up past the
        // target stays, or tapping any row above it would empty the AutoPlay list.
        val remainingAutoplay = currentTimeline.subList(targetIndex + 1, currentTimeline.size)
            .filter { it.queueTier == QueueTier.AUTOPLAY }

        return when (targetSong.queueTier) {
            QueueTier.AUTOPLAY -> {
                val sourceTitle = targetSong.playbackSource?.ifBlank { targetSong.title } ?: targetSong.title
                val promotedTarget = targetSong.copy(
                    playbackSource = sourceTitle,
                    playbackSourceType = targetSong.playbackSourceType ?: PlaybackSourceType.QUEUE,
                ).asQueueEntry(QueueTier.CONTEXT)
                listOf(promotedTarget) + allFutureUserQueue + remainingAutoplay
            }
            QueueTier.CONTEXT -> {
                val remainingContext = currentTimeline.subList(targetIndex + 1, currentTimeline.size)
                    .filter { it.queueTier == QueueTier.CONTEXT }
                listOf(targetSong) + allFutureUserQueue + remainingContext + remainingAutoplay
            }
            QueueTier.USER_QUEUE -> {
                val subsequentUserQueue = currentTimeline.subList(targetIndex + 1, currentTimeline.size)
                    .filter { it.queueTier == QueueTier.USER_QUEUE }
                val futureContext = currentTimeline.subList(currentIndex + 1, currentTimeline.size)
                    .filter { it.queueTier == QueueTier.CONTEXT }
                val futureAutoplay = currentTimeline.subList(currentIndex + 1, currentTimeline.size)
                    .filter { it.queueTier == QueueTier.AUTOPLAY }
                listOf(targetSong) + subsequentUserQueue + futureContext + futureAutoplay
            }
        }
    }

    /**
     * Executes a semantic queue jump on [host], preserving history up to [QueueHost.currentIndex]
     * and avoiding unintended reshuffling.
     *
     * Deliberately the only jump pruning on desktop: the original had two. This one answers a tap on
     * a queue row. The other sat on the session side, `PlaybackService.kt:6527-6571`'s
     * `seekTo(mediaItemIndex, positionMs)` for the notification shade and Android Auto, which used
     * [skippedByQueueJump] to pick the bypassed span, deleted by the target's tier, and moved the
     * target up to the row right after the playhead. Desktop has the one entry point until the media
     * session arrives with slice 6, so that half stays on Android — which leaves [skippedByQueueJump]
     * without a production caller here. It is kept as the verbatim carry it is, alongside
     * [MAX_QUEUE_HISTORY], and pinned by the tests in QueueHistoryTest and QueueCoordinatorTest.
     */
    fun jumpToQueueItem(
        host: QueueHost,
        targetIndex: Int,
        cachedTimeline: List<Song>? = null,
    ) {
        val currentIndex = host.currentIndex
        val count = host.itemCount
        if (targetIndex !in 0 until count) return

        if (targetIndex <= currentIndex) {
            // Backward jump or same track: seek in history without modifying playlist
            host.jumpTo(targetIndex, 0L)
            return
        }

        val currentTimeline = cachedTimeline?.takeIf { it.size == count }
            ?: (0 until count).mapNotNull { host.songAt(it) }
        val newUpcoming = buildJumpQueue(currentTimeline, currentIndex, targetIndex) ?: return

        // Retain played history up to and including currentIndex so backward navigation works
        val history = (0..currentIndex).mapNotNull { host.songAt(it) }

        val newPlaylist = history + newUpcoming
        val newTargetIndex = history.size // First track of newUpcoming
        host.setTimeline(newPlaylist, newTargetIndex)
        // Media3 had to be prepared here: a player sitting in its idle state accepts a new
        // playlist and then says nothing until it is armed. The desktop player takes a URL and
        // sounds, so arming and playing are the same act — playCurrent() is that one call.
        host.playCurrent()
    }
}
