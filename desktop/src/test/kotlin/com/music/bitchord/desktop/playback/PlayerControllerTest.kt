package com.music.bitchord.desktop.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.playback.PlaybackStatus
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.RepeatMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The queue pump, wired to an engine that makes no sound.
 *
 * Everything here is about the *seam* — a row ends, the list moves, the new row's URL gets
 * resolved and handed to the engine, and the state the UI reads is republished. The queue's
 * own arithmetic is [com.music.bitchord.playback.QueueTimelineTest]'s business; what this file
 * pins is that exactly one advance happens per engine callback, that nothing but
 * `onFinished` advances, that a resolve which is no longer anybody's gets thrown away rather
 * than played or reported, and that the state carries the names the original's `PlayerState`
 * does (spec §3.1).
 */
class PlayerControllerTest {

    /** The crossfade setting is process-wide: a case that raised it puts it back, or the next
     * controller built here would come up ticking. */
    @AfterTest
    fun resetCrossfadeSetting() {
        AppSettings.crossfadeSeconds.value = 0
    }

    /** A bounded wait for something a wall-clock coroutine is about to do — see the quality
     * swap's blend-clear poll, the one place a test cannot pump a scheduler for. */
    private fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }

    private fun song(id: String) = Song(id, "T$id", "A$id", null)

    /** A controller whose stream resolution is instant: this test is about the queue, not the network. */
    private fun controller(): Pair<PlayerController, FakeAudioEngine> {
        val engine = FakeAudioEngine()
        // QueueShuffle.enabled is process-wide and Task 5's cases flip it; playSongs
        // consults it, so a case that expects list order must pin it off first.
        QueueShuffle.setEnabled(false)
        // This file is about the pump, not the top-up: AutoPlay off keeps the
        // last-row resolves from reaching for a radio queue. AutoplayTopUpTest
        // pins the opposite value for the same reason.
        AppSettings.setAutoplay(false)
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

    /**
     * Resolves that stay open until the case releases them.
     *
     * The only way to get two of them overlapping: every dispatcher used here runs inline, so the
     * plain [PlayerController.resolveUrl] seam has finished before the call that asked for it
     * returns. Held through the same seam — it is a `suspend` one already, which is the whole
     * point — so nothing is added to production to make a resolve wait.
     */
    private class HeldResolves {
        private val gates = mutableMapOf<String, CompletableDeferred<String>>()

        /** Install as [PlayerController.resolveUrl]. */
        suspend fun resolve(videoId: String): String =
            gates.getOrPut(videoId) { CompletableDeferred<String>() }.await()

        /** Has the controller reached this row's resolve at all — i.e. is it truly in flight? */
        fun isInFlight(videoId: String): Boolean = gates.containsKey(videoId)

        /** Let it come back with the URL the ordinary seam would have handed out. */
        fun complete(videoId: String) {
            gates.getValue(videoId).complete("https://test/$videoId")
        }

        /** Let it come back having failed. */
        fun fail(videoId: String, error: Throwable) {
            gates.getValue(videoId).completeExceptionally(error)
        }
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
    fun `the snapshot carries the controller's own position from the very first read`() {
        val (player, _) = controller()
        // Before anything is published this reads the seed. Left to `PlayerState()`'s default the
        // first snapshot would carry a *second* PlaybackPosition and only start carrying this one
        // at the first publish — the identity trap PlaybackPosition's own KDoc warns about.
        assertSame(player.position, player.state.value.position)
        player.playOneOff(song("a"), PlaybackSourceType.SEARCH, "S", null)
        assertSame(player.position, player.state.value.position)
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
    fun `a backward jump plays the target row and asks the engine for no seek`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b"), song("c")), index = 2,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        // A backward jump goes through the queue's `jumpTo(index, positionMs)`, which names a
        // position to the host *before* it names the play. The engine must not hear about that
        // position: the play that follows stops it and restarts the clock, and the fraction a
        // `seekTo(ms)` would build is divided by the length of the row being left behind.
        engine.length(30_000L)
        engine.tick(15_000L)
        assertEquals(30_000L, player.state.value.durationMs)
        player.jumpTo(0)
        assertEquals(0, player.state.value.queueIndex)
        assertEquals(listOf("https://test/c", "https://test/a"), engine.playedUrls)
        assertEquals(emptyList(), engine.seekFractions)
        // What the jump's position is allowed to touch is the display, and the new row re-seats
        // that to zero itself — which is what the scrubber shows while the new row resolves.
        assertEquals(0L, player.position.positionMs)
    }

    @Test
    fun `volume and position reach the engine`() {
        val (player, engine) = controller()
        player.playOneOff(song("a"), PlaybackSourceType.SEARCH, "S", null)
        engine.tick(45_000L)
        assertEquals(45_000L, player.state.value.position.positionMs)
        assertEquals(45_000L, player.position.positionMs)
        // The volume reaches the engine twice here, and both writes matter: once when the
        // controller is built, and again on every row, because a fresh VLC media would otherwise
        // keep whatever volume the last one had. Asserted as the whole list so the second one
        // cannot go missing.
        assertEquals(listOf(80, 80), engine.volumes)
        assertEquals(80, player.volume.value)
        player.setVolume(40)
        assertEquals(40, player.volume.value)
        assertEquals(40, engine.volumes.last())
        // Clamped on the way in, so a slider read one past its end cannot ask libvlc for 150%.
        player.setVolume(150)
        assertEquals(100, player.volume.value)
        assertEquals(100, engine.volumes.last())
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
    fun `the one snapshot the chrome reads carries song and flags across an advance`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        // Task 11 moved Shell and the two pages onto `state`, so the slice-1 pair of stores this
        // case used to compare is gone: there is one snapshot, and what is worth pinning is that
        // an advance re-seats the song *and* keeps the audio flags pointing at the row that now
        // sounds, rather than the pair agreeing with each other about the row that used to.
        assertEquals("a", player.state.value.song?.videoId)
        assertTrue(player.state.value.isPlaying)
        assertFalse(player.state.value.isLoading)
        engine.finish()
        assertEquals("b", player.state.value.song?.videoId)
        assertTrue(player.state.value.isPlaying)
        assertFalse(player.state.value.isLoading)
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
        assertTrue(player.status.value is PlaybackStatus.ResolveFailed)
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

    @Test
    fun `the next button walks the list and is never the completion path`() {
        val (player, engine) = controller()
        player.playFrom(listOf(song("a"), song("b"), song("c")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        // Repeat-one is where the two answers differ, so it is where a `next()` that ran the
        // completion path would show itself: a press walks forward, an ended row replays in place.
        player.setRepeat(RepeatMode.ONE)
        player.next()
        assertEquals(1, player.state.value.queueIndex)
        assertTrue(player.state.value.isPlaying)
        assertEquals(listOf("https://test/a", "https://test/b"), engine.playedUrls)
        engine.finish()
        assertEquals(1, player.state.value.queueIndex)
        assertEquals(listOf("https://test/a", "https://test/b", "https://test/b"), engine.playedUrls)
        player.next()
        assertEquals(2, player.state.value.queueIndex)
        // Now the tail, in the one mode that cannot wrap: a press there is a no-op rather than an
        // error — the row under the needle keeps sounding and nothing gets reported.
        player.next()
        assertEquals(2, player.state.value.queueIndex)
        assertEquals(
            listOf("https://test/a", "https://test/b", "https://test/b", "https://test/c"),
            engine.playedUrls,
        )
        assertNull(player.status.value)
    }

    @Test
    fun `a prefetch that fails is swallowed`() {
        val (player, engine) = controller()
        player.onPrefetch = { throw IllegalStateException("the read-ahead refused") }
        player.playFrom(listOf(song("a"), song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        // The row that asked for it is untouched and says nothing about it: it sounds, its
        // spinner is off, and the status stays empty rather than carrying a prefetch's error.
        assertEquals(listOf("https://test/a"), engine.playedUrls)
        assertTrue(player.state.value.isPlaying)
        assertFalse(player.state.value.isLoading)
        assertNull(player.status.value)
        // Nor is it the next row's: the advance resolves that one for real and it sounds.
        engine.finish()
        assertEquals(listOf("https://test/a", "https://test/b"), engine.playedUrls)
        assertNull(player.status.value)
    }

    // ---- overlapping resolves -------------------------------------------------------
    //
    // Nothing cancels a resolve, so one can always come back after the playhead has moved on:
    // a row that ends mid-resolve is the ordinary way, since starting a row stops the engine and
    // libvlc answers that with an end-of-media event. What follows pins that a late resolve gets
    // thrown away — its URL, its cleared status and its cleared spinner alike — and especially
    // that being thrown away is not the same as having failed.

    @Test
    fun `a resolve superseded while in flight plays nothing and leaves the newer row alone`() {
        val (player, engine) = controller()
        val held = HeldResolves()
        player.resolveUrl = held::resolve
        player.playFrom(listOf(song("a"), song("b"), song("c")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        assertTrue(held.isInFlight("a"))
        // The row ends while a's URL is still being fetched: the advance takes the queue forward,
        // and a's fetch is still out.
        engine.finish()
        assertTrue(held.isInFlight("b"))
        assertEquals(PlaybackStatus.Resolving, player.status.value)
        assertTrue(player.state.value.isLoading)
        // a comes back. It is a row the playhead has already left, so none of it may land: not the
        // URL, and not the status/spinner writes that belong with it — those two are what would
        // silence *b*'s "resolving" and stop *b*'s spinner.
        held.complete("a")
        assertEquals(emptyList(), engine.playedUrls)
        assertEquals(PlaybackStatus.Resolving, player.status.value)
        assertTrue(player.state.value.isLoading)
        // b, the row that is actually current, still reports for itself.
        held.complete("b")
        assertEquals(listOf("https://test/b"), engine.playedUrls)
        assertNull(player.status.value)
        assertFalse(player.state.value.isLoading)
    }

    @Test
    fun `a superseded resolve that fails reports nothing`() {
        val (player, engine) = controller()
        val held = HeldResolves()
        player.resolveUrl = held::resolve
        player.playFrom(listOf(song("a"), song("b"), song("c")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        engine.finish()
        assertTrue(held.isInFlight("b"))
        held.fail("a", IllegalStateException("no stream"))
        // Discarded, so the failure path never ran: b's message is still the one on screen, and
        // there is no "resolve failed" for a row nobody is listening to.
        assertFalse(player.status.value is PlaybackStatus.ResolveFailed)
        assertEquals(PlaybackStatus.Resolving, player.status.value)
        assertTrue(player.state.value.isLoading)
        assertEquals(emptyList(), engine.playedUrls)
        held.complete("b")
        assertEquals(listOf("https://test/b"), engine.playedUrls)
        assertNull(player.status.value)
    }

    @Test
    fun `a cancelled resolve is not counted as a failed one`() {
        val (player, engine) = controller()
        // The shape `runCatching` gets wrong: a cancellation is not an error, and reporting it as
        // one would overwrite the row that replaced this.
        player.resolveUrl = { throw CancellationException("the resolve was called off") }
        player.playOneOff(song("a"), PlaybackSourceType.SEARCH, "S", null)
        assertEquals(emptyList(), engine.playedUrls)
        assertFalse(player.status.value is PlaybackStatus.ResolveFailed)
        // Nothing was reported at all: the row is still the one it was, still waiting, and the
        // reporting belongs to whoever replaces it.
        assertEquals(PlaybackStatus.Resolving, player.status.value)
        assertTrue(player.state.value.isLoading)
    }

    // ---- crossfade handoffs --------------------------------------------------------
    //
    // The machine's own arithmetic is CrossfadeControllerTest's business; what this file pins is
    // the seam it hands off across: that a blend really makes the incoming engine the session,
    // that the queue follows it at the first sound rather than at the outgoing track's end, and
    // that the outgoing engine's own end — the advance it used to carry — moves nothing.

    /** A controller with a 6-second crossfade standing on two fake engines, driven by hand. */
    private class CrossfadeCase {
        private fun song(id: String) = Song(id, "T$id", "A$id", null)

        val engine = FakeAudioEngine()
        var standby: FakeAudioEngine? = null
        val player = PlayerController(CoroutineScope(Dispatchers.Unconfined), engine).apply {
            // The same pins `controller()` makes — process-wide state a case
            // must not inherit from the one before it.
            QueueShuffle.setEnabled(false)
            AppSettings.setAutoplay(false)
            resolveUrl = { videoId -> "https://test/$videoId" }
            resolveDispatcher = Dispatchers.Unconfined
            queueDispatcher = Dispatchers.Unconfined
            crossfadeStandbyEngine = { FakeAudioEngine().also { standby = it } }
            // The tick loop would race these tests on a wall clock; the machine moves when the
            // test says so instead.
            crossfade.manualTicks = true
            AppSettings.crossfadeSeconds.value = 6
        }

        /** Plays a three-row queue and walks it to the moment b becomes audible. */
        fun handOffToB(): FakeAudioEngine {
            player.playFrom(listOf(song("a"), song("b"), song("c")), index = 0,
                source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
            engine.length(180_000)
            engine.tick(170_000) // remaining 10s = fade 6s + lead 4s: the arm window opens
            player.crossfade.tickForTest()
            val standby = standby!!
            standby.length(200_000)
            engine.tick(174_000) // remaining 6s: the fade point
            player.crossfade.tickForTest()
            return standby
        }
    }

    @Test
    fun `a crossfade makes the incoming engine the session and the queue follows it`() {
        val case = CrossfadeCase()
        val player = case.player
        val engine = case.engine
        val standby = case.handOffToB()
        // The incoming track was armed, not played: it never went through a resolve-and-play,
        // and it is audible now.
        assertEquals(listOf("https://test/b"), standby.preparedUrls)
        assertEquals(emptyList(), standby.playedUrls)
        assertTrue(standby.isPlaying)
        assertTrue(player.state.value.isPlaying)
        // The session moved: the needle, the metadata, the scrubber's span and the clock.
        assertEquals(1, player.state.value.queueIndex)
        assertEquals("b", player.state.value.song?.videoId)
        assertEquals(200_000L, player.state.value.durationMs)
        assertEquals(0L, player.position.positionMs)
        // The engine the rest of the class talks to is the incoming one — the user's volume
        // control now reaches it, and only it.
        player.setVolume(50)
        assertEquals(50, standby.volumes.last())
        // The outgoing engine's own end is not an advance: its advance was forgotten at the
        // handoff, and the queue it can no longer move stays put.
        engine.finish()
        assertEquals(1, player.state.value.queueIndex)
        assertEquals("b", player.state.value.song?.videoId)
        assertEquals(listOf("https://test/a"), engine.playedUrls)
        // Its end is the blend's completion, though.
        player.crossfade.tickForTest()
        assertFalse(player.crossfade.isTransitioning())
        // And the queue advances from the incoming engine when IT runs out — exactly one advance
        // per track, as ever.
        standby.finish()
        assertEquals(2, player.state.value.queueIndex)
        assertEquals(listOf("https://test/c"), standby.playedUrls)
    }

    @Test
    fun `a skip during a fade stands the blend down and the new row does not wait for it`() {
        val case = CrossfadeCase()
        val player = case.player
        val engine = case.engine
        val standby = case.handOffToB()
        standby.tick(1_000)
        player.crossfade.tickForTest()
        assertEquals(77, engine.volumes.last()) // the outgoing track is mid-blend
        player.next()
        // The skip is instant — c sounds now, on the session engine — and the outgoing one is
        // being ramped away rather than cut.
        assertEquals(2, player.state.value.queueIndex)
        assertEquals(listOf("https://test/c"), standby.playedUrls)
        assertTrue(player.crossfade.isTransitioning())
        // The tail only ever goes down from here.
        val descending = engine.volumes.filter { it < 80 }
        assertTrue(descending.isNotEmpty())
        assertEquals(descending.sortedDescending(), descending)
        // BAIL_MS is 120ms of wall clock; past it the machine is idle and the retired engine
        // is stopped.
        Thread.sleep(140)
        player.crossfade.tickForTest()
        assertFalse(player.crossfade.isTransitioning())
        assertFalse(engine.isPlaying)
    }

    @Test
    fun `repeat-one is never crossfaded into itself`() {
        val case = CrossfadeCase()
        val player = case.player
        val engine = case.engine
        player.playFrom(listOf(song("a"), song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        player.setRepeat(RepeatMode.ONE)
        engine.length(180_000)
        engine.tick(170_000)
        player.crossfade.tickForTest()
        // Deep inside the arm window, with a row queued after the loop: still nothing armed.
        assertNull(case.standby)
        assertFalse(player.crossfade.isTransitioning())
        // The loop itself still replays in place.
        engine.finish()
        assertEquals(listOf("https://test/a", "https://test/a"), engine.playedUrls)
    }

    /**
     * The equaliser's handoff seam: the curve moves with the session, at the
     * standby's creation and again at the handoff — the two places the
     * output-device switch applies, with the settings collector keeping both
     * halves in step the way upstream fed both processors.
     */
    @Test
    fun `the crossfade handoff equalises the incoming engine like the session it becomes`() {
        AppSettings.setEqualizerEnabled(true)
        AppSettings.setEqualizerBands(com.music.bitchord.playback.EqualizerPreset.ROCK.bands)
        try {
            val case = CrossfadeCase()
            val engine = case.engine
            val standby = case.handOffToB()
            // The session engine was equalised by the collector's first fire —
            // a curve in the settings means a tuning on the engine, no play
            // required.
            assertTrue(engine.equalizers.isNotEmpty(), "the collector should have applied at startup")
            assertTrue(engine.equalizers.last()!!.enabled)
            assertEquals(10, engine.equalizers.last()!!.bandAmps.size)
            // The standby was equalised when it was built, and again when the
            // handoff made it the session — the same curve both times, which
            // is what keeps a blend from straddling two equalisers.
            assertTrue(standby.equalizers.isNotEmpty(), "the standby should have been equalised at creation")
            val applied = standby.equalizers.last()!!
            assertTrue(applied.enabled)
            assertEquals(engine.equalizers.last(), applied)
        } finally {
            AppSettings.setEqualizerEnabled(false)
            AppSettings.setEqualizerBands(com.music.bitchord.playback.EqualizerPreset.FLAT.bands)
        }
    }

    // ── The playback dials (slice 18) ────────────────────────────────────────

    /**
     * The speed dial reaches the engines the moment it moves and every engine
     * created after it: the session through the watcher, the standby at its
     * creation — the one act that keeps a blend from straddling two rates.
     */
    @Test
    fun `the speed dial reaches both engines`() {
        val case = CrossfadeCase()
        val engine = case.engine
        assertTrue(
            engine.speeds.contains(1.0f),
            "the watcher's first fire should have applied the stored speed",
        )
        AppSettings.setPlaybackSpeed(1.5f)
        try {
            assertEquals(1.5f, engine.speeds.last())
            val standby = case.handOffToB()
            assertEquals(
                1.5f,
                standby.speeds.last(),
                "a standby built after the dial moved must not open its blend at normal speed",
            )
        } finally {
            AppSettings.setPlaybackSpeed(1.0f)
        }
    }

    /** The switch reaches the engine seam even where this build's engine cannot act on it. */
    @Test
    fun `the skip-silence switch reaches the engine seam`() {
        val (_, engine) = controller()
        AppSettings.setSkipSilence(true)
        try {
            assertTrue(engine.skipSilences.last())
        } finally {
            AppSettings.setSkipSilence(false)
        }
    }

    /**
     * The loudness correction is the figure the resolver recorded, negated,
     * riding the preamp — with the equaliser off, a flat equalizer that carries
     * the gain and nothing else.
     */
    @Test
    fun `a track's loudness figure lands as the engine's preamp`() {
        val (player, engine) = controller()
        try {
            AppSettings.setLoudnessNormalization(true)
            // A track 6 dB too loud asks for -6 dB, well inside the window.
            com.music.bitchord.data.innertube.StreamResolver.seedLoudness("a", 6.0)
            player.playFrom(
                listOf(song("a")), index = 0,
                source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null,
            )
            val applied = engine.equalizers.last()!!
            assertTrue(applied.enabled, "the gain needs the equalizer attached to exist")
            assertEquals(-6f, applied.preampDb)
            assertTrue(applied.bandAmps.all { it == 0f }, "flat: the gain is the only change")
        } finally {
            AppSettings.setLoudnessNormalization(true)
            com.music.bitchord.data.innertube.StreamResolver.seedLoudness("a", null)
        }
    }

    /** The boost window's top: a too-quiet track is corrected only to +3 dB, upstream's own ceiling. */
    @Test
    fun `the loudness correction never boosts past the window's ceiling`() {
        val (player, engine) = controller()
        try {
            AppSettings.setLoudnessNormalization(true)
            com.music.bitchord.data.innertube.StreamResolver.seedLoudness("a", -10.0)
            player.playFrom(
                listOf(song("a")), index = 0,
                source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null,
            )
            assertEquals(3f, engine.equalizers.last()!!.preampDb)
        } finally {
            AppSettings.setLoudnessNormalization(true)
            com.music.bitchord.data.innertube.StreamResolver.seedLoudness("a", null)
        }
    }

    /** Switched off, the correction is nothing: the tuning stays what the equaliser settings made it. */
    @Test
    fun `loudness off leaves the tuning as the equalizer settings made it`() {
        val (player, engine) = controller()
        try {
            com.music.bitchord.data.innertube.StreamResolver.seedLoudness("a", -6.0)
            AppSettings.setLoudnessNormalization(false)
            player.playFrom(
                listOf(song("a")), index = 0,
                source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null,
            )
            assertEquals(EqualizerTuning.OFF, engine.equalizers.last())
        } finally {
            AppSettings.setLoudnessNormalization(true)
            com.music.bitchord.data.innertube.StreamResolver.seedLoudness("a", null)
        }
    }

    // ── The audio/video version toggle (slice 21) ────────────────────────────

    @Test
    fun `a video row converts to the catalogue song and keeps its place`() {
        val (player, engine) = controller()
        val videoRow = song("a").copy(isVideo = true)
        player.resolveAlternateVersion = { Song("a-audio", "T", "A", null) }
        player.playFrom(listOf(videoRow, song("b")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        assertEquals("https://test/a", engine.playedUrls.last())
        player.toggleAudioVersion()
        // The row became the song, marked as having come from a video.
        assertEquals("a-audio", player.state.value.song?.videoId)
        assertTrue(player.state.value.song!!.isVideoOrigin)
        // The stream moved to the converted track at the position the
        // listener was at — the engine never left "playing".
        assertEquals("https://test/a-audio", engine.playedUrls.last())
        assertTrue(player.state.value.isPlaying)
    }

    @Test
    fun `a paused row converts without touching the engine`() {
        val (player, engine) = controller()
        player.resolveAlternateVersion = { Song("a-video", "T", "A", null).copy(isVideo = true) }
        player.playFrom(listOf(song("a")), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        engine.pause()
        val playsBefore = engine.playedUrls.size
        player.toggleAudioVersion()
        assertEquals("a-video", player.state.value.song?.videoId)
        assertEquals(playsBefore, engine.playedUrls.size, "a paused row converts on its next play, not now")
    }

    @Test
    fun `a resolve that answers the same row converts nothing`() {
        val (player, engine) = controller()
        val row = song("a")
        player.resolveAlternateVersion = { row }
        player.playFrom(listOf(row), index = 0,
            source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
        val urlBefore = engine.playedUrls.last()
        player.toggleAudioVersion()
        assertEquals("a", player.state.value.song?.videoId)
        assertEquals(urlBefore, engine.playedUrls.last())
    }

    @Test
    fun `a quality change waits out a blend and lands once it clears`() {
        val case = CrossfadeCase()
        val player = case.player
        val engine = case.engine
        val standby = case.handOffToB()
        var auditions = 0
        player.auditionEngine = {
            auditions++
            FakeAudioEngine().also { fake ->
                // The proof, answered the way vlcj's events would.
                fake.onPlayHook = {
                    fake.reportStats("mp4a", 256, 44_100, 2)
                    fake.length(200_000)
                }
            }
        }
        player.repickUrl = { _, _ -> "https://test/b-alt" }
        try {
            player.setAudioQuality(AudioQuality.HIGH)
            // The proof ran on its own silent engine — it costs the blend
            // nothing — but the landing waited: the blend's engines are
            // untouched while it is in flight.
            assertEquals(1, auditions)
            assertEquals(emptyList(), standby.playedUrls)
            assertTrue(player.crossfade.isTransitioning())
            // The blend ends; the waiting swap lands on the session engine.
            // The landing runs on the wall clock (the blend-clear poll), so
            // the test meets it with a bounded wait rather than by freezing
            // the poll interval into an assertion.
            engine.finish()
            player.crossfade.tickForTest()
            assertFalse(player.crossfade.isTransitioning())
            waitUntil { standby.playedUrls.isNotEmpty() }
            assertEquals("https://test/b-alt", standby.playedUrls.last())
        } finally {
            AppSettings.setAudioQuality(AudioQuality.LOSSLESS)
        }
    }
}
