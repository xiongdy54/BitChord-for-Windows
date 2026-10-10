package com.music.bitchord.desktop.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sleep timer where it actually does something: the watcher that pauses on
 * a deadline, and the "after this song" branch that declines the queue's one
 * advance. The state machine's own maths is [SleepTimerTest]'s; what this file
 * pins is that the two hooks are wired to the two moments that matter — time
 * passing, and a track ending — and that neither can start audio the user
 * asked to stop.
 *
 * The deadline runs on virtual time: the watcher computes its wait off the
 * real monotonic clock, then sleeps inside the test scheduler, so
 * [advanceTimeBy] is the wall clock here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerPlaybackTest {

    private class Rig(val scope: TestScope) {
        private fun song(id: String) = Song(id, "T$id", "A$id", null)

        val engine = FakeAudioEngine()
        val player = PlayerController(
            CoroutineScope(StandardTestDispatcher(scope.testScheduler)),
            engine,
        )

        init {
            QueueShuffle.setEnabled(false)
            AppSettings.setAutoplay(false)
            player.resolveUrl = { videoId -> "https://test/$videoId" }
            player.resolveDispatcher = StandardTestDispatcher(scope.testScheduler)
            player.queueDispatcher = StandardTestDispatcher(scope.testScheduler)
        }

        /** A queue of [ids] with row [index] sounding — the stage every case plays on. */
        fun playQueue(ids: List<String>, index: Int = 0) {
            player.playFrom(
                ids.map { song(it) },
                index = index,
                source = PlaybackSourceType.SEARCH,
                sourceTitle = "S",
                sourceId = null,
            )
            scope.advanceUntilIdle()
        }

        /** Scheduler hops with no virtual time — an armed-but-unexpired timer must not fire. */
        fun drain() {
            repeat(64) { scope.runCurrent() }
        }
    }

    @AfterTest
    fun cleanUp() {
        // A pending delay left in the test scope, or a timer left armed in the
        // process-wide object, would leak into whichever case ran next.
        SleepTimer.cancel()
        // The device tests wrote the real settings file (the AppSettings
        // singleton owns it); put back what they found. The other cases left
        // no mark and are not charged a disk write for one.
        if (deviceCaptured) AppSettings.setAudioOutputDevice(deviceBefore, nameBefore)
    }

    private var deviceCaptured = false
    private var deviceBefore: String? = null
    private var nameBefore: String? = null

    private fun captureDevice() {
        deviceCaptured = true
        deviceBefore = AppSettings.outputDeviceId.value
        nameBefore = AppSettings.outputDeviceName.value
    }

    @Test
    fun `a deadline that arrives pauses the engine and spends the timer`() = runTest {
        val rig = Rig(this)
        rig.playQueue(listOf("a", "b"))
        assertTrue(rig.engine.isPlaying)

        SleepTimer.start(15)
        // One minute of virtual time: not yet.
        advanceTimeBy(60_000)
        rig.drain()
        assertTrue(rig.engine.isPlaying)
        assertTrue(SleepTimer.isRunning)

        // The rest of the way: the watcher pauses and cancels — one pause,
        // not a replay, and the row stays the row.
        advanceTimeBy(15 * 60_000)
        rig.drain()
        assertFalse(rig.engine.isPlaying)
        assertFalse(SleepTimer.isRunning)
        assertEquals("a", rig.player.state.value.song?.videoId)
        assertEquals(listOf("https://test/a"), rig.engine.playedUrls)
    }

    @Test
    fun `an unexpired deadline does not pause`() = runTest {
        val rig = Rig(this)
        rig.playQueue(listOf("a", "b"))
        SleepTimer.start(15)
        rig.drain()
        assertTrue(rig.engine.isPlaying)
    }

    @Test
    fun `after this song holds at the finished row instead of advancing`() = runTest {
        val rig = Rig(this)
        rig.playQueue(listOf("a", "b", "c"))
        SleepTimer.startAfterTrack()
        rig.engine.finish()
        rig.drain()
        // Nothing sounds after the song: the next row was never resolved, the
        // needle never moved, and the timer is spent.
        assertEquals(listOf("https://test/a"), rig.engine.playedUrls)
        assertEquals("a", rig.player.state.value.song?.videoId)
        assertFalse(rig.engine.isPlaying)
        assertFalse(SleepTimer.isRunning)
    }

    @Test
    fun `after this song declines the repeat-one replay too`() = runTest {
        val rig = Rig(this)
        rig.playQueue(listOf("a", "b"))
        rig.player.setRepeat(RepeatMode.ONE)
        rig.drain()
        SleepTimer.startAfterTrack()
        rig.engine.finish()
        rig.drain()
        // Upstream's comment is the reason this case exists: with repeat-one
        // on, every end is also a transition, and the timer that paused beside
        // it would otherwise never fire at all.
        assertEquals(listOf("https://test/a"), rig.engine.playedUrls)
        assertFalse(SleepTimer.isRunning)
    }

    @Test
    fun `without the timer the finished row advances as always`() = runTest {
        val rig = Rig(this)
        rig.playQueue(listOf("a", "b", "c"))
        rig.engine.finish()
        rig.drain()
        assertEquals("https://test/b", rig.engine.playedUrls.last())
        assertEquals("b", rig.player.state.value.song?.videoId)
    }

    @Test
    fun `the queue running dry spends an armed deadline`() = runTest {
        val rig = Rig(this)
        rig.playQueue(listOf("a"))
        SleepTimer.start(15)
        rig.engine.finish()
        rig.drain()
        // Upstream cancels on STATE_ENDED for the same reason: no transition
        // will ever close the last track out, so there is nothing left to
        // interrupt — and the pending wait must not outlive its queue.
        assertFalse(SleepTimer.isRunning)
        assertFalse(rig.engine.isPlaying)
    }

    @Test
    fun `the device switch writes the setting and tells the engine`() = runTest {
        captureDevice()
        val rig = Rig(this)
        rig.player.setAudioOutputDevice("dev-1", "Headphones")
        assertEquals("dev-1", AppSettings.outputDeviceId.value)
        assertEquals("Headphones", AppSettings.outputDeviceName.value)
        assertEquals("dev-1", rig.engine.outputDevice)
        assertEquals(1, rig.engine.deviceCalls)
        // The same answer twice is one act: the settings row and a re-opened
        // dialog may both land on what is already stored.
        rig.player.setAudioOutputDevice("dev-1", "Headphones")
        assertEquals(1, rig.engine.deviceCalls)
        // Null is the system default, and the engine hears the unsetting.
        rig.player.setAudioOutputDevice(null, null)
        assertNull(AppSettings.outputDeviceId.value)
        assertNull(rig.engine.outputDevice)
    }

    @Test
    fun `a stored device is applied to the engine before the first play`() = runTest {
        captureDevice()
        AppSettings.outputDeviceId.value = "stored-dev"
        val rig = Rig(this)
        // No track has sounded; the endpoint was already named to the engine.
        assertEquals("stored-dev", rig.engine.outputDevice)
    }
}
