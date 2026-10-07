package com.music.bitchord.desktop.playback

import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.QueueShuffle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The measurement's path from the engine to [NerdStats], wired through the
 * controller: the report must land bound to the row the needle is on, the old
 * row's figures must leave with it, and a resolver-only track (no engine
 * bitrate stated) still carries the recorded figure as a fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerStatsTest {

    private fun song(id: String) = Song(id, "T$id", "A$id", null)

    private class Rig(val scope: TestScope) {
        val engine = FakeAudioEngine()
        val player = PlayerController(CoroutineScope(StandardTestDispatcher(scope.testScheduler)), engine)

        init {
            QueueShuffle.setEnabled(false)
            AppSettings.setAutoplay(false)
            player.resolveUrl = { videoId -> "https://test/$videoId" }
            player.resolveDispatcher = StandardTestDispatcher(scope.testScheduler)
            player.queueDispatcher = StandardTestDispatcher(scope.testScheduler)
        }
    }

    @AfterTest
    fun cleanUp() {
        NerdStats.clear()
    }

    @Test
    fun `the engine's report lands bound to the row that is playing`() = runTest {
        val rig = Rig(this)
        with(rig) {
            player.playFrom(listOf(song("a"), song("b")), index = 0,
                source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
            runCurrent()
            engine.reportStats(codecName = "flac", bitrateKbps = 900, sampleRateHz = 44_100, channels = 2)
            runCurrent()
            assertEquals("a", NerdStats.currentVideoId())
            val snapshot = NerdStats.current.value
            assertEquals("flac", snapshot?.mimeType)
            assertEquals(900, snapshot?.bitrateKbps)
            assertEquals(44_100, snapshot?.sampleRateHz)
            assertEquals(2, snapshot?.channels)
        }
    }

    @Test
    fun `the old row's figures leave with the row`() = runTest {
        val rig = Rig(this)
        with(rig) {
            player.playFrom(listOf(song("a"), song("b")), index = 0,
                source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
            runCurrent()
            engine.reportStats("flac", 900, 44_100, 2)
            runCurrent()
            assertEquals("a", NerdStats.currentVideoId())
            rig.engine.finish() // b starts; the measurement was a's
            runCurrent()
            assertNull(NerdStats.current.value)
            assertNull(NerdStats.currentVideoId())
        }
    }

    @Test
    fun `a bitrate the engine could not state falls back to the resolver's record`() = runTest {
        val rig = Rig(this)
        with(rig) {
            NerdStats.onStreamPicked("a", kbps = 160, mimeType = "audio/webm; codecs=opus")
            player.playFrom(listOf(song("a")), index = 0,
                source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
            runCurrent()
            engine.reportStats(codecName = "opus", bitrateKbps = null, sampleRateHz = 48_000, channels = 2)
            runCurrent()
            assertEquals("a", NerdStats.currentVideoId())
            val snapshot = NerdStats.current.value
            assertEquals(160, snapshot?.bitrateKbps)
            assertEquals("opus", snapshot?.mimeType)
        }
    }
}
