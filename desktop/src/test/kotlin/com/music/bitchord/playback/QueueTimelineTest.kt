// The desktop-only half of the queue: the state ExoPlayer used to hold implicitly.
//
// The plan's 15 cases, at the plan's expected values, plus three of its members that the plan
// produced but never called. Three mechanical corrections to the plan's snippets, none of them a
// value: `asQueueEntry` is a member extension of `Song`, so the 27-row fixtures map per element
// rather than handing a List<Song> to it; `assertEquals` cannot infer a common type argument
// across `List<String>` and `MutableList<String?>`; and `moveRow`'s playhead delta needs both
// halves of the move — see the case that pins it.
package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueTimelineTest {

    private fun song(id: String, tier: QueueTier = QueueTier.CONTEXT) = Song(
        videoId = id, title = "T$id", artist = "A$id", thumbnailUrl = null, queueTier = tier,
    )
    private fun rows(vararg ids: String, tier: QueueTier = QueueTier.CONTEXT) =
        ids.map { song(it, tier).asQueueEntry(tier) }
    private fun started(
        vararg ids: String,
        tier: QueueTier = QueueTier.CONTEXT,
        startIndex: Int = 0,
    ): QueueTimeline = QueueTimeline().apply { start(rows(*ids, tier = tier), startIndex) }
    private val search = QueueSource("S", PlaybackSourceType.SEARCH)

    /** playFrom consults the shuffle flag, so every case starts from a known order. */
    @BeforeTest
    fun shuffleOff() {
        QueueShuffle.setEnabled(false)
    }

    @Test
    fun `finishing walks forward and stops at the tail`() {
        val t = started("a", "b", "c")
        assertEquals("b", t.onFinished())
        assertEquals("c", t.onFinished())
        assertNull(t.onFinished())
        assertEquals("c", t.songAt(t.currentIndex)?.videoId)
    }

    @Test
    fun `repeat-one replays the same row`() {
        val t = started("a", "b")
        t.repeatMode = RepeatMode.ONE
        assertEquals("a", t.onFinished())
        assertEquals(0, t.currentIndex)
    }

    @Test
    fun `repeat-all wraps at the tail`() {
        val t = started("a", "b")
        t.repeatMode = RepeatMode.ALL
        assertEquals("b", t.onFinished())
        assertEquals("a", t.onFinished())
    }

    @Test
    fun `back restarts past ten seconds and steps before it`() {
        val t = started("a", "b", "c", startIndex = 1)
        assertEquals("b", t.previous(positionMs = QueueTimeline.BACK_RESTARTS_AFTER_MS + 1))
        assertEquals(1, t.currentIndex)
        assertEquals("a", t.previous(positionMs = 2_000L))
        assertEquals(0, t.currentIndex)
    }

    @Test
    fun `back at the head with nothing behind it does nothing`() {
        val t = started("a", "b")
        assertNull(t.previous(positionMs = 0L))
        assertEquals(0, t.currentIndex)
    }

    @Test
    fun `advancing has exactly one entry point`() {
        val t = started("a", "b", "c")
        val moves = mutableListOf<String?>()
        t.onChanged = { moves += t.songAt(t.currentIndex)?.videoId }
        t.onFinished()
        // The expected list is spelled List<String?> because that is what the recorder holds —
        // `songAt` answers null once the queue is empty, and assertEquals cannot infer a common T
        // across List<String> and MutableList<String?>. Recording the nulls is the point: an extra
        // publish that landed on nothing would show up here instead of passing unnoticed.
        assertEquals(listOf<String?>("b"), moves)
    }

    @Test
    fun `a context queue keeps rows the user queued ahead of the playhead`() {
        val t = started("a", "b")
        t.enqueueLast(song("u1"))
        val startedId = t.playFrom(listOf(song("c1"), song("c2")), selectedIndex = 1, source = search)
        assertEquals("c2", startedId)
        assertEquals(listOf("c1", "c2", "u1"), t.snapshot().map { it.videoId })
        assertEquals(1, t.currentIndex)
    }

    @Test
    fun `a one-off tap replaces the context and keeps the user queue`() {
        val t = started("a", "b")
        t.enqueueLast(song("u"))
        t.startOneOff(song("tap"), source = search)
        assertEquals(listOf("tap", "u"), t.snapshot().map { it.videoId })
        assertEquals(0, t.currentIndex)
    }

    @Test
    fun `play next lands after the current row, add to queue joins the user block`() {
        val t = started("a", "b", "c")
        t.enqueueNext(song("n"))
        assertEquals(listOf("a", "n", "b", "c"), t.snapshot().map { it.videoId })
        t.enqueueLast(song("q"))
        // findUserQueueInsertionIndex returns the first non-USER row after the
        // playhead (QueueCoordinator:145-148), so user rows form one block in the slots
        // straight after the current track rather than going to the list tail.
        assertEquals(listOf("a", "n", "q", "b", "c"), t.snapshot().map { it.videoId })
        assertEquals(QueueTier.USER_QUEUE, t.snapshot()[2].queueTier)
    }

    @Test
    fun `history beyond the window rotates rather than vanishing under repeat-all`() {
        val t = QueueTimeline()
        t.repeatMode = RepeatMode.ALL
        t.start((1..27).map { song("t$it").asQueueEntry(QueueTier.CONTEXT) }, startIndex = 0)
        repeat(26) { t.onFinished() }
        assertEquals(27, t.itemCount)
        assertEquals(25, t.currentIndex)
        assertEquals("t2", t.snapshot().first().videoId)
        assertEquals("t1", t.snapshot().last().videoId)
    }

    @Test
    fun `history beyond the window is dropped when repeat is off`() {
        val t = QueueTimeline()
        t.start((1..27).map { song("t$it").asQueueEntry(QueueTier.CONTEXT) }, startIndex = 0)
        repeat(26) { t.onFinished() }
        assertEquals(26, t.itemCount)
        assertEquals(25, t.currentIndex)
        assertEquals("t2", t.snapshot().first().videoId)
    }

    @Test
    fun `removing a row behind the playhead does not move the playhead`() {
        // "The playhead" here is the track under the needle, not its number: the index drops from
        // 2 to 1 because a row behind it left, and the assertion below is what the QueueHost
        // contract is about — the same Song stays selected.
        val t = started("a", "b", "c", startIndex = 2)
        t.removeRow(0)
        assertEquals(1, t.currentIndex)
        assertEquals("c", t.songAt(t.currentIndex)?.videoId)
    }

    @Test
    fun `removing the current row is refused`() {
        val t = started("a", "b", "c", startIndex = 1)
        t.removeRow(1)
        assertEquals(listOf("a", "b", "c"), t.snapshot().map { it.videoId })
    }

    // Three cases the plan's list does not have, for the members it produces but never calls:
    // `next()` and `moveRow()` have no caller until Task 6 and Task 12, and both are index
    // arithmetic that is wrong by one in a way the coordinator tests cannot see.

    @Test
    fun `the transport's next steps forward and stops at the tail`() {
        val t = started("a", "b")
        assertEquals("b", t.next())
        assertNull(t.next())
        assertEquals(1, t.currentIndex)
    }

    @Test
    fun `dragging a row keeps the needle on the track it was on`() {
        val t = started("a", "b", "c", startIndex = 2)
        // Both halves of this edit happen behind the needle, so the row leaving index 0 and the
        // same row arriving at index 1 cancel: the needle does not move at all.
        t.moveRow(0, 1)
        assertEquals(listOf("b", "a", "c"), t.snapshot().map { it.videoId })
        assertEquals(2, t.currentIndex)
        // The dragged row *is* the current one: it travels with the needle onto `to`.
        t.moveRow(2, 0)
        assertEquals(listOf("c", "b", "a"), t.snapshot().map { it.videoId })
        assertEquals(0, t.currentIndex)
        assertEquals("c", t.songAt(t.currentIndex)?.videoId)
    }

    @Test
    fun `jumping to a row re-seats the rows ahead of the needle and sounds it`() {
        val t = started("a", "b", "c", startIndex = 0)
        val played = mutableListOf<String>()
        val seeks = mutableListOf<String>()
        t.onPlay = { song -> played += song.videoId }
        t.onSeek = { song, _ -> seeks += song.videoId }

        // Forward: buildJumpQueue keeps history up to the playhead and re-seats the rest, so the
        // target lands at history.size = 1 rather than at the tapped index 2. playCurrent sounds
        // it; nothing seeked, so onSeek stays empty.
        t.jumpToRow(2)
        assertEquals(listOf("a", "c"), t.snapshot().map { it.videoId })
        assertEquals(1, t.currentIndex)
        assertEquals(listOf("c"), played)
        assertEquals(emptyList(), seeks)

        // Backward into the history already there: the list is untouched and this is a seek.
        t.jumpToRow(0)
        assertEquals(listOf("a", "c"), t.snapshot().map { it.videoId })
        assertEquals(0, t.currentIndex)
        assertEquals(listOf("c", "a"), played)
        assertEquals(listOf("a"), seeks)
    }

    @Test
    fun `shuffle leaves the current row and pins the user queue at the head`() {
        val t = QueueTimeline()
        t.start(
            listOf(
                song("now"), song("c1"), song("c2"), song("c3"),
                song("u1", QueueTier.USER_QUEUE),
            ).map { it.asQueueEntry(it.queueTier) },
            startIndex = 0,
        )
        t.toggleShuffle()
        // QueueShuffle.shuffle builds order as userQueueIndices + shuffledSection
        // (context) + shuffledSection(autoplay) — the user row therefore lands in
        // slot from = currentIndex + 1 no matter how the context rows permute.
        assertEquals("now", t.songAt(0)?.videoId)
        assertEquals("u1", t.songAt(1)?.videoId)
        assertEquals(5, t.itemCount)
        assertEquals(0, t.currentIndex)
        // toggle() persists through AppSettings.setShuffleEnabled(true), which writes
        // the real settings.properties. Put the default back so a test run does not
        // leave the user's next session starting shuffled.
        AppSettings.setShuffleEnabled(false)
        QueueShuffle.setEnabled(false)
    }

    @Test
    fun `starting a queue while shuffle is on leads with the picked row`() {
        QueueShuffle.setEnabled(true)
        val t = QueueTimeline()
        val startedId = t.playFrom(
            listOf(song("c1"), song("c2"), song("c3")), selectedIndex = 2, source = search,
        )
        // startingOrder puts songs[startIndex] first, and queueStartIndex then says
        // the playhead is 0 rather than 2 — the two halves of PlayerConnection:762-771
        // that must always move together.
        assertEquals("c3", startedId)
        assertEquals(0, t.currentIndex)
        assertEquals("c3", t.songAt(0)?.videoId)
        assertEquals(3, t.itemCount)
        QueueShuffle.setEnabled(false)
    }

    /**
     * The shuffle cases above restore the flag inline; this belongs to the failure path, where
     * that restore never runs. `QueueShuffle` and `AppSettings` are process-wide singletons and
     * Gradle runs every class in this worker JVM, so a `true` left here would be the next class's
     * starting state — and `build.gradle.kts`'s LOCALAPPDATA redirect only fixes which file the
     * write lands in, not what the singletons hold.
     */
    @AfterTest
    fun leaveTheShuffleFlagAtItsDefault() {
        AppSettings.setShuffleEnabled(false)
        QueueShuffle.setEnabled(false)
    }
}
