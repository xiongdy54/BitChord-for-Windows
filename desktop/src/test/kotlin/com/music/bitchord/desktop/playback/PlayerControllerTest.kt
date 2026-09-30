package com.music.bitchord.desktop.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The queue pump, wired to an engine that makes no sound.
 *
 * Everything here is about the *seam* — a row ends, the list moves, the new row's URL gets
 * resolved and handed to the engine, and the state the UI reads is republished. The queue's
 * own arithmetic is [com.music.bitchord.playback.QueueTimelineTest]'s business; what this file
 * pins is that exactly one advance happens per engine callback, that nothing but
 * `onFinished` advances, and that the state carries the names the original's `PlayerState`
 * does (spec §3.1).
 */
class PlayerControllerTest {

    private fun song(id: String) = Song(id, "T$id", "A$id", null)

    /** A controller whose stream resolution is instant: this test is about the queue, not the network. */
    private fun controller(): Pair<PlayerController, FakeAudioEngine> {
        val engine = FakeAudioEngine()
        // QueueShuffle.enabled is process-wide and Task 5's cases flip it; playSongs
        // consults it, so a case that expects list order must pin it off first.
        QueueShuffle.setEnabled(false)
        val player = PlayerController(CoroutineScope(Dispatchers.Unconfined), engine)
        player.resolveUrl = { videoId -> "https://test/$videoId" }
        player.resolveDispatcher = Dispatchers.Unconfined
        // The controller's default is the Swing EDT. A queue mutation and the state
        // publication that follows it are confined to that one dispatcher, so a JVM
        // test without a display has to name a different one — Unconfined runs the
        // posted block on the thread that posted it, which makes every hop inline.
        player.queueDispatcher = Dispatchers.Unconfined
        return player to engine
    }

    @Test
    fun `finishing advances to the next row and plays it`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b"), song("c")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        assertEquals("https://test/a", engine.playedUrls.last())
        engine.finish(); assertEquals("https://test/b", engine.playedUrls.last())
        engine.finish(); assertEquals("https://test/c", engine.playedUrls.last())
        assertEquals(2, player.state.value.queueIndex)
        engine.finish()
        assertEquals(listOf("https://test/a", "https://test/b", "https://test/c"), engine.playedUrls)
        assertTrue(!player.state.value.isPlaying)
    }

    @Test
    fun `stopping at the tail is not an error`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        engine.finish(); engine.finish()
        assertNull(player.status.value)
        assertFalse(player.state.value.isLoading)
        assertFalse(player.state.value.isPlaying)
    }

    @Test
    fun `the state carries the names the original's PlayerState uses`() {
        val (player, _) = controller()
        player.playFrom(listOf(song("a"), song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        val s = player.state.value
        assertEquals("a", s.song?.videoId)
        assertEquals(2, s.queue.size)
        assertEquals(0, s.queueIndex)
        assertEquals(false, s.hasPrevious)
        assertEquals(true, s.hasNext)
        assertEquals(RepeatMode.OFF, s.repeatMode)
        assertEquals(false, s.isQualityUpgraded)
    }

    @Test
    fun `repeat-all wraps and hasNext stays honest`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        player.cycleRepeat(); assertEquals(RepeatMode.ALL, player.state.value.repeatMode)
        assertEquals(true, player.state.value.hasNext)
        engine.finish(); engine.finish(); engine.finish()
        assertEquals(1, player.state.value.queueIndex)
    }

    @Test
    fun `back past ten seconds replays the current row`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b")), index = 1,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        engine.tick(11_000L); player.previous()
        assertEquals(1, player.state.value.queueIndex)
        assertEquals(2, player.state.value.queue.size)
        engine.tick(2_000L); player.previous()
        assertEquals(0, player.state.value.queueIndex)
    }

    @Test
    fun `a queued row does not interrupt what is playing`() {
        val (player, engine) = controller()
        player.playOneOff(song("now"), PlaybackSourceType.SEARCH, "S", null)
        player.enqueueLast(song("later"))
        player.enqueueNext(song("nextone"))
        assertEquals(listOf("https://test/now"), engine.playedUrls)
        assertEquals(listOf("now", "nextone", "later"), player.state.value.queue.map { it.videoId })
        assertEquals(QueueTier.USER_QUEUE, player.state.value.queue[1].queueTier)
    }

    @Test
    fun `the row after the one that started is resolved ahead of time`() {
        val (player, _) = controller()
        val prefetched = mutableListOf<String>()
        player.onPrefetch = { videoId -> prefetched += videoId }
        player.playFrom(listOf(song("a"), song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        assertEquals(listOf("b"), prefetched)
    }

    @Test
    fun `the last row prefetches nothing`() {
        val (player, _) = controller()
        val prefetched = mutableListOf<String>()
        player.onPrefetch = { videoId -> prefetched += videoId }
        player.playOneOff(song("solo"), PlaybackSourceType.SEARCH, "S", null)
        assertEquals(emptyList(), prefetched)
    }

    @Test
    fun `a jump from the queue panel plays the target row`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b"), song("c")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        player.jumpTo(2)
        // jumpToQueueItem keeps history up to the old playhead and re-seats the rest,
        // so the target lands at history.size == 1, not at 2 — the list becomes [a, c, …].
        assertEquals(1, player.state.value.queueIndex)
        assertEquals("https://test/c", engine.playedUrls.last())
    }

    @Test
    fun `volume and position reach the engine`() {
        val (player, engine) = controller()
        player.playOneOff(song("a"), PlaybackSourceType.SEARCH, "S", null)
        engine.tick(45_000L)
        assertEquals(45_000L, player.state.value.position.positionMs)
        assertEquals(45_000L, player.position.positionMs)
    }

    @Test
    fun `repeat-one replays in place and lights no glyph it cannot honour`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b")), index = 1,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        // cycleRepeat only walks OFF→ALL→OFF; the mode itself is set the way Main.kt restores it.
        player.setRepeat(RepeatMode.ONE)
        assertEquals(RepeatMode.ONE, player.state.value.repeatMode)
        // At the tail with nothing to wrap to: a next button that lit here would be the dead
        // button spec §2 rules out, because QueueTimeline.next() returns null in this mode.
        assertFalse(player.state.value.hasNext)
        assertTrue(player.state.value.hasPrevious)
        engine.finish()
        assertEquals(1, player.state.value.queueIndex)
        assertEquals(listOf("https://test/b", "https://test/b"), engine.playedUrls)
    }

    @Test
    fun `back asks the engine where it is, not the display`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        engine.tick(11_000L)
        // The advance to b re-seats the *display* clock at zero, because a new row is a new
        // media and whatever the scrubber was showing belongs to the old one. The engine's own
        // position is the number the restart threshold is about: the display is a UI value that
        // a drag writes (Task 10's scrubber does exactly that), and a half-dragged value must
        // not decide whether back replays this row or steps to the last.
        engine.finish()
        assertEquals(0L, player.position.positionMs)
        assertEquals(11_000L, engine.timeMs)
        player.previous()
        assertEquals(1, player.state.value.queueIndex)
        assertEquals(listOf("https://test/a", "https://test/b", "https://test/b"), engine.playedUrls)
    }

    @Test
    fun `the slice-1 flows the chrome reads still track the snapshot`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        // Shell.kt:78-81 and the two pages' `song != null` guards read these three rather than
        // `state` until Task 11 rewires them; one writer keeps them from drifting.
        assertEquals("a", player.current.value?.videoId)
        assertTrue(player.playing.value)
        assertFalse(player.loading.value)
        engine.finish()
        assertEquals("b", player.current.value?.videoId)
    }

    @Test
    fun `the transitional play entry point does not restart what it is already playing`() {
        val (player, engine) = controller()
        // What HomePage.kt:61 and SearchPage.kt:60 call until Task 12 moves them to playFrom,
        // including slice 1's own double-tap guard.
        player.play(song("a"))
        player.play(song("a"))
        assertEquals(listOf("https://test/a"), engine.playedUrls)
        player.play(song("b"))
        assertEquals(listOf("https://test/a", "https://test/b"), engine.playedUrls)
        assertEquals(0, player.state.value.queueIndex)
        assertEquals(listOf("b"), player.state.value.queue.map { it.videoId })
    }

    @Test
    fun `a failed resolve moves no audio`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        player.resolveUrl = { throw IllegalStateException("no stream") }
        engine.finish()
        // The row still advances — that is the list's decision, made before any
        // resolving — but the engine is left holding the row that last sounded.
        assertEquals(1, player.state.value.queueIndex)
        assertEquals(listOf("https://test/a"), engine.playedUrls)
        assertFalse(player.state.value.isLoading)
        assertTrue(player.status.value!!.startsWith("resolve failed"))
    }

    @Test
    fun `an advance onto a hand-queued row keeps it, and prunes it once played`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        player.enqueueLast(song("u"))
        assertEquals(listOf("a", "u", "b"), player.state.value.queue.map { it.videoId })
        engine.finish()
        assertEquals("https://test/u", engine.playedUrls.last())
        assertEquals(listOf("a", "u", "b"), player.state.value.queue.map { it.videoId })
        assertEquals(1, player.state.value.queueIndex)
        // One advance, one row still in front of it. The next advance plays a CONTEXT
        // row, and the consumed user row behind the needle is what that prunes.
        engine.finish()
        assertEquals("https://test/b", engine.playedUrls.last())
        assertEquals(listOf("a", "b"), player.state.value.queue.map { it.videoId })
        assertEquals(1, player.state.value.queueIndex)
    }
}
