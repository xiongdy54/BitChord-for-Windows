// Ported from app/src/test/java/com/music/bitchord/QueueCoordinatorTest.kt — the cases that say
// what the queue algorithms *do*, kept at their original expectations. The MediaItem-projection
// cases the Android suite needed (tier read back off a Bundle, mediaId comparisons) are gone with
// Media3; FakeQueueHost stands in for that suite's java.lang.reflect.Proxy fake Player.
package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueCoordinatorTest {

    private fun song(id: String, tier: QueueTier = QueueTier.CONTEXT) = Song(
        videoId = id, title = "T$id", artist = "A$id", thumbnailUrl = null, queueTier = tier,
    )
    private val source = QueueSource("Search", PlaybackSourceType.SEARCH, "sid")

    @Test
    fun `a context queue keeps the live user queue where it was`() {
        val result = QueueCoordinator.buildContextQueue(
            currentTimeline = listOf(song("now"), song("old", QueueTier.USER_QUEUE), song("stale")),
            currentIndex = 0,
            newContextSongs = (1..3).map { song("c$it") },
            selectedIndex = 0,
            contextSource = source,
        )
        assertEquals(listOf("c1", "old", "c2", "c3"), result.timeline.map { it.videoId })
        assertEquals(0, result.startIndex)
    }

    @Test
    fun `a context queue starts at the tapped row`() {
        val result = QueueCoordinator.buildContextQueue(
            currentTimeline = emptyList(), currentIndex = 0,
            newContextSongs = (1..3).map { song("c$it") }, selectedIndex = 2, contextSource = source,
        )
        assertEquals(2, result.startIndex)
        assertEquals("c3", result.timeline[result.startIndex].videoId)
    }

    @Test
    fun `a one-off queue is the tapped track plus what the user queued`() {
        val timeline = QueueCoordinator.buildOneOffQueue(
            currentTimeline = listOf(song("old"), song("keep", QueueTier.USER_QUEUE)),
            currentIndex = 0,
            tappedSong = song("tap"),
            source = source,
        )
        assertEquals(listOf("tap", "keep"), timeline.map { it.videoId })
    }

    @Test
    fun `play next lands right after the current track, add to queue at the user tier's tail`() {
        val timeline = listOf(
            song("now"), song("u1", QueueTier.USER_QUEUE), song("c1"), song("u2", QueueTier.USER_QUEUE),
        )
        assertEquals(1, QueueCoordinator.findUserQueueInsertionIndex(timeline, currentIndex = 0, isNext = true))
        assertEquals(2, QueueCoordinator.findUserQueueInsertionIndex(timeline, currentIndex = 0, isNext = false))
    }

    @Test
    fun `a queue entry gets its id once and keeps it`() {
        val tagged = song("v1").asQueueEntry(QueueTier.USER_QUEUE)
        assertEquals(tagged.queueEntryId, tagged.asQueueEntry(QueueTier.CONTEXT).queueEntryId)
        assertEquals(QueueTier.USER_QUEUE, tagged.queueTier)
    }

    @Test
    fun `a forward jump keeps the user queue and the rows after the target`() {
        val jumped = QueueCoordinator.buildJumpQueue(
            listOf(
                song("now"), song("c1"), song("skip", QueueTier.USER_QUEUE),
                song("c2"), song("target"), song("c3"),
            ),
            currentIndex = 0, targetIndex = 4,
        )!!
        assertEquals(listOf("target", "skip", "c3"), jumped.map { it.videoId })
    }

    @Test
    fun `a backward jump changes nothing`() {
        assertNull(QueueCoordinator.buildJumpQueue(listOf(song("a"), song("b")), currentIndex = 1, targetIndex = 0))
    }

    @Test
    fun `jumping to an autoplay row promotes it into the context tier`() {
        val jumped = QueueCoordinator.buildJumpQueue(
            listOf(song("now"), song("r1", QueueTier.AUTOPLAY), song("r2", QueueTier.AUTOPLAY)),
            currentIndex = 0, targetIndex = 1,
        )!!
        assertEquals(QueueTier.CONTEXT, jumped.first().queueTier)
        assertEquals(PlaybackSourceType.QUEUE, jumped.first().playbackSourceType)
        // The plan (docs/superpowers/plans/2026-09-30-desktop-slice2-now-playing-queue.md:322) wrote
        // this as `jumped.drop(1)` over the same expected list. The AUTOPLAY branch returns the
        // promoted target first (app/.../QueueCoordinator.kt:271), so ["r1", "r2"] *is* the whole
        // jump: the assertion's subject was wrong, not its value — and the original suite states a
        // case of this shape as a full-list map (app/.../QueueCoordinatorTest.kt:389).
        assertEquals(listOf("r1", "r2"), jumped.map { it.videoId })
    }

    @Test
    fun `the context queue labels every row with the source it came from`() {
        val result = QueueCoordinator.buildContextQueue(
            currentTimeline = emptyList(), currentIndex = 0,
            newContextSongs = listOf(song("c1")), selectedIndex = 0, contextSource = source,
        )
        assertEquals("Search", result.timeline.single().playbackSource)
        assertEquals(PlaybackSourceType.SEARCH, result.timeline.single().playbackSourceType)
        assertEquals("sid", result.timeline.single().playbackSourceId)
    }

    @Test
    fun `an empty context is refused before anything is built`() {
        val result = QueueCoordinator.buildContextQueue(
            currentTimeline = listOf(song("a")), currentIndex = 0,
            newContextSongs = emptyList(), selectedIndex = 0, contextSource = source,
        )
        assertTrue(result.timeline.isEmpty()); assertEquals(0, result.startIndex)
    }

    // ---- the three functions that mutate a host, ported from the original's
    //      `clearUserQueue removes only USER_QUEUE items after currentIndex`,
    //      `consumePlayedUserQueue prunes user queue items when entering context`
    //      and `jumpToQueueItem updates player retaining history and setting new
    //      upcoming items` (app/.../QueueCoordinatorTest.kt:290-326, :477-523) ----

    @Test
    fun `clearing the user queue removes only the USER_QUEUE rows after the playhead`() {
        val host = FakeQueueHost(
            listOf(
                song("u0", QueueTier.USER_QUEUE),
                song("playing"),
                song("u1", QueueTier.USER_QUEUE),
                song("u2", QueueTier.USER_QUEUE),
                song("c1"),
                song("a1", QueueTier.AUTOPLAY),
            ),
            currentIndex = 1,
        )

        QueueCoordinator.clearUserQueue(host, host::tierAt)

        assertEquals(listOf("u0", "playing", "c1", "a1"), host.items.map { it.videoId })
        assertEquals(1, host.currentIndex)
    }

    @Test
    fun `played user queue rows are pruned once playback enters the context`() {
        val host = FakeQueueHost(
            listOf(
                song("u1", QueueTier.USER_QUEUE),
                song("u2", QueueTier.USER_QUEUE),
                song("c1"),
                song("c2"),
            ),
            currentIndex = 2,
        )

        QueueCoordinator.consumePlayedUserQueue(host, host::tierAt)

        assertEquals(listOf("c1", "c2"), host.items.map { it.videoId })
        // Both rows went away behind the playhead, so the playhead moved down with
        // them — which is what ExoPlayer does, and what the guard on the next case
        // turns on.
        assertEquals(0, host.currentIndex)
    }

    @Test
    fun `nothing is pruned while the current row is still a user queue row`() {
        val host = FakeQueueHost(
            listOf(song("u1", QueueTier.USER_QUEUE), song("u2", QueueTier.USER_QUEUE), song("c1")),
            currentIndex = 1,
        )

        QueueCoordinator.consumePlayedUserQueue(host, host::tierAt)

        assertEquals(listOf("u1", "u2", "c1"), host.items.map { it.videoId })
        assertEquals(1, host.currentIndex)
    }

    @Test
    fun `nothing is consumed when the host has no row at the playhead`() {
        val host = FakeQueueHost(listOf(song("u1", QueueTier.USER_QUEUE)), currentIndex = 1)

        QueueCoordinator.consumePlayedUserQueue(host, host::tierAt)

        assertEquals(listOf("u1"), host.items.map { it.videoId })
    }

    @Test
    fun `a jump keeps the played history and installs the rebuilt upcoming rows`() {
        val host = FakeQueueHost(
            listOf(
                song("history0"),
                song("u1", QueueTier.USER_QUEUE),
                song("c1"),
                song("a1", QueueTier.AUTOPLAY),
            ),
        )

        QueueCoordinator.jumpToQueueItem(host, targetIndex = 3, cachedTimeline = host.items.toList())

        assertEquals(listOf("history0", "a1", "u1"), host.items.map { it.videoId })
        assertEquals(1, host.currentIndex)
        assertEquals(listOf("a1"), host.played)
        // Rebuilt the list, then played it — no seek, because the target is already index 1.
        assertEquals(listOf("playCurrent()"), host.playbackCalls)
    }

    @Test
    fun `a backward jump seeks without touching the playlist`() {
        val host = FakeQueueHost(
            listOf(song("a"), song("b"), song("c")),
            currentIndex = 2,
        )

        QueueCoordinator.jumpToQueueItem(host, targetIndex = 0, cachedTimeline = host.items.toList())

        assertEquals(listOf("a", "b", "c"), host.items.map { it.videoId })
        assertEquals(0, host.currentIndex)
        assertEquals(listOf("a"), host.played)
        // The other channel: a backward jump is a seek to 0 into history that is already there, so
        // it never goes through playCurrent — and playCurrent would not have reset the position.
        assertEquals(listOf("jumpTo(0, 0)"), host.playbackCalls)
    }

    @Test
    fun `a snapshot that no longer matches the host is not trusted`() {
        val host = FakeQueueHost(
            listOf(
                song("history0"),
                song("u1", QueueTier.USER_QUEUE),
                song("c1"),
                song("a1", QueueTier.AUTOPLAY),
            ),
        )

        QueueCoordinator.jumpToQueueItem(host, targetIndex = 3, cachedTimeline = (1..5).map { song("stale$it") })

        assertEquals(listOf("history0", "a1", "u1"), host.items.map { it.videoId })
    }

    @Test
    fun `a tap outside the queue does nothing`() {
        val host = FakeQueueHost(listOf(song("a"), song("b")))

        QueueCoordinator.jumpToQueueItem(host, targetIndex = 5)

        assertEquals(listOf("a", "b"), host.items.map { it.videoId })
        assertTrue(host.played.isEmpty())
    }

    // ---- the QueueHost playhead contract, pinned here because Tasks 4-6 implement the interface
    //      and not this fake: an edit that changes the number of rows behind the needle has to move
    //      the needle with them, which is what ExoPlayer did implicitly. ----

    @Test
    fun `an edit behind the playhead moves it down with the rows it removed`() {
        val host = FakeQueueHost((1..5).map { song("e$it") }, currentIndex = 3)

        // The two rows behind the needle go and one takes their place: the needle still sits on e4.
        host.replaceRange(0, 2, listOf(song("x")))

        assertEquals(listOf("x", "e3", "e4", "e5"), host.items.map { it.videoId })
        assertEquals(2, host.currentIndex)
    }

    @Test
    fun `an equal size edit ahead of the playhead leaves it where it is`() {
        val host = FakeQueueHost((1..4).map { song("e$it") }, currentIndex = 0)

        // The reorder Task 4's shuffle does at from = currentIndex + 1: same size, all ahead.
        host.replaceRange(1, 3, listOf(song("e3"), song("e2")))

        assertEquals(listOf("e1", "e3", "e2", "e4"), host.items.map { it.videoId })
        assertEquals(0, host.currentIndex)
    }

    // ---- the history window, PlaybackService.kt:2712-2724. The counts are queueHistoryTrimCount's
    //      own arithmetic, not chosen to fit: 27 rows with the needle on the last one is the first
    //      shape that expires anything, because (26 - 25) is 1 while (25 - 25) is 0. ----

    @Test
    fun `history beyond the window rotates to the tail under repeat-all`() {
        val host = FakeQueueHost((1..27).map { song("t$it") }, currentIndex = 26)
        host.repeatMode = RepeatMode.ALL

        host.trimHistory()

        // moveMediaItem(0, mediaItemCount - 1) once: the list keeps its length, t1 goes to the end,
        // and everything behind the needle slides down a slot with it.
        assertEquals(25, host.currentIndex)
        assertEquals(27, host.items.size)
        assertEquals("t2", host.items.first().videoId)
        assertEquals("t1", host.items.last().videoId)
    }

    @Test
    fun `history beyond the window is deleted when repeat is off`() {
        val host = FakeQueueHost((1..27).map { song("t$it") }, currentIndex = 26)

        host.trimHistory()

        assertEquals(26, host.items.size)
        assertEquals(25, host.currentIndex)
        assertEquals("t2", host.items.first().videoId)
    }

    @Test
    fun `nothing is trimmed until the playhead passes the window`() {
        val host = FakeQueueHost((1..26).map { song("t$it") }, currentIndex = 25)

        host.trimHistory()

        // 25 - 25 is 0, so nothing here is expired yet and the call must be inert: a rotation case
        // built on this shape would pass whether or not trimHistory did anything, which is why the
        // two above take 27 rows and stop the needle on the last one.
        assertEquals(26, host.items.size)
        assertEquals(25, host.currentIndex)
    }

    @Test
    fun `the history window is the app's own number`() {
        // The three cases above read the window through the same constant the fake trims with, so
        // without this pin they would silently follow it wherever it went.
        assertEquals(25, MAX_QUEUE_HISTORY)
        assertEquals(0, queueHistoryTrimCount(currentIndex = 25))
        assertEquals(1, queueHistoryTrimCount(currentIndex = 26))
    }

    @Test
    fun `a jump that only steps over the next row has nothing to delete`() {
        assertNull(skippedByQueueJump(currentIndex = 3, targetIndex = 4))
        // The plan (docs/superpowers/plans/2026-09-30-desktop-slice2-now-playing-queue.md:570) wrote
        // 4..4 here. `(currentIndex + 1) until targetIndex` — QueueHistory.kt:18, carried verbatim —
        // is 4..5 for 3 -> 6, the same shape the original states as 3..6 for 2 -> 7
        // (app/src/test/java/com/music/bitchord/QueueHistoryTest.kt:23). Kept the plan's indices and
        // corrected the range: its value cannot be produced by the function it is testing.
        assertEquals(4..5, skippedByQueueJump(currentIndex = 3, targetIndex = 6))
    }
}
