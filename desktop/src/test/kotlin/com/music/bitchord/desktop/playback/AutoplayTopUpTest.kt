package com.music.bitchord.desktop.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AUTOPLAY_EMPTY_REFRESH_DELAY_MS
import com.music.bitchord.playback.MAX_QUEUED_AUTOPLAY
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.RepeatMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The AutoPlay top-up, driven against a fake radio.
 *
 * The queue pump's own arithmetic is [com.music.bitchord.playback.AutoplayTest]'s
 * and `QueueTimelineTest`'s business; what this file pins is the controller's
 * half — that a queue reaching its last row gets suggestions behind it, that the
 * switch's two answers edit the queue the way the switch claims, that repeat-all
 * takes the suggestions away and gives them back, and that an empty radio answer
 * retries once on virtual time and then stops.
 *
 * The single live setting [AppSettings.autoplay] reads and writes is the real
 * one, so every case pins it the way `QueueShuffleTierTest` pins shuffle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoplayTopUpTest {

    private fun song(id: String) = Song(id, "T$id", "A$id", null)

    /** A controller on a virtual-time scheduler, plus the fake radio's call log. */
    private class Rig(val scope: TestScope, val song: (String) -> Song) {
        val engine = FakeAudioEngine()
        val player = PlayerController(CoroutineScope(StandardTestDispatcher(scope.testScheduler)), engine)
        val radioCalls = mutableListOf<String>()

        init {
            QueueShuffle.setEnabled(false)
            player.resolveUrl = { videoId -> "https://test/$videoId" }
            player.resolveDispatcher = StandardTestDispatcher(scope.testScheduler)
            player.queueDispatcher = StandardTestDispatcher(scope.testScheduler)
            player.loadAutoplay = { _, seed, limit ->
                radioCalls += seed.videoId
                Result.success(
                    List(limit) { song("s${seed.videoId}-$it").copy(queueTier = QueueTier.AUTOPLAY) },
                )
            }
            AppSettings.setAutoplay(true)
        }

        /** The queue's tail, as the UI sees it. */
        val queue get() = player.state.value.queue
    }

    @Test
    fun `a queue reaching its last row fills the AUTOPLAY tier behind it`() = runTest {
        val rig = Rig(this, ::song)
        with(rig) {
            player.playFrom(listOf(song("a"), song("b")), index = 0,
                source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
            engine.finish() // b starts; b is the last row
            runCurrent()
            val autoplay = queue.filter { it.queueTier == QueueTier.AUTOPLAY }
            assertEquals(MAX_QUEUED_AUTOPLAY, autoplay.size)
            assertEquals(1, radioCalls.size)
            assertEquals("b", radioCalls.first())
        }
    }

    @Test
    fun `a queue that is not at its tail is not topped up`() = runTest {
        val rig = Rig(this, ::song)
        with(rig) {
            player.playFrom(listOf(song("a"), song("b"), song("c")), index = 0,
                source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
            runCurrent()
            assertTrue(radioCalls.isEmpty())
        }
    }

    @Test
    fun `switching autoplay off takes the suggestions back`() = runTest {
        val rig = Rig(this, ::song)
        with(rig) {
            player.playFrom(listOf(song("a")), index = 0,
                source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
            runCurrent()
            assertTrue(queue.any { it.queueTier == QueueTier.AUTOPLAY })
            AppSettings.setAutoplay(false)
            runCurrent()
            assertTrue(queue.none { it.queueTier == QueueTier.AUTOPLAY })
        }
    }

    @Test
    fun `repeat-all stashes the tail and restore puts it back`() = runTest {
        val rig = Rig(this, ::song)
        with(rig) {
            player.playFrom(listOf(song("a")), index = 0,
                source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
            runCurrent()
            val filled = queue.filter { it.queueTier == QueueTier.AUTOPLAY }
            assertTrue(filled.isNotEmpty())

            player.cycleRepeat() // OFF -> ALL: the suggestions step aside
            runCurrent()
            assertTrue(queue.none { it.queueTier == QueueTier.AUTOPLAY })

            player.cycleRepeat() // ALL -> OFF: they come back behind the same track
            runCurrent()
            assertEquals(filled, queue.filter { it.queueTier == QueueTier.AUTOPLAY })
        }
    }

    @Test
    fun `a stash taken behind a track the loop played past is refused`() = runTest {
        val rig = Rig(this, ::song)
        with(rig) {
            player.playFrom(listOf(song("a")), index = 0,
                source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
            runCurrent()
            player.cycleRepeat() // OFF -> ALL: stash taken behind a
            runCurrent()
            // The loop plays on; a wrapped queue is still the same single row, so
            // drive past the seed by queueing by hand and restarting instead:
            player.playOneOff(song("z"), source = PlaybackSourceType.SEARCH,
                sourceTitle = "S", sourceId = null)
            runCurrent()
            player.cycleRepeat() // ALL -> OFF with the needle on z: refused
            runCurrent()
            assertTrue(queue.none { it.queueTier == QueueTier.AUTOPLAY })
        }
    }

    @Test
    fun `an empty radio answer retries once on virtual time and then stops`() = runTest {
        val rig = Rig(this, ::song)
        with(rig) {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            player.loadAutoplay = { _, _, _ ->
                calls += 1
                gate.await() // hold the first call so the test can gate each pass
                Result.success(emptyList())
            }
            player.playFrom(listOf(song("a")), index = 0,
                source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
            runCurrent()
            assertEquals(1, calls)
            gate.complete(Unit)
            runCurrent()
            // The retry is scheduled, not immediate:
            assertEquals(1, calls)
            advanceTimeBy(AUTOPLAY_EMPTY_REFRESH_DELAY_MS)
            runCurrent()
            assertEquals(2, calls) // exactly one retry
            gate.complete(Unit)
            advanceTimeBy(AUTOPLAY_EMPTY_REFRESH_DELAY_MS + 1_000)
            runCurrent()
            assertEquals(2, calls) // and never a third
        }
    }
}
