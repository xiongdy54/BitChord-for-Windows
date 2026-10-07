package com.music.bitchord.desktop.playback

import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.playback.QueueShuffle
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
import kotlin.test.assertTrue

/**
 * The quality swap's decision table: a setting change re-picks the ladder for
 * the track playing, and the candidate is only allowed near the speaker once a
 * silent second engine has proved it. Every guard here exists because a
 * missed swap is a quieter failure than an interrupted song.
 *
 * The proof is scripted on the fake audition engine itself (its play hook
 * answers the way vlcj's events would), so the whole chain — settings
 * collector, swap job, resolve, proof — runs to quiescence with
 * [TestScope.advanceUntilIdle] and no test has to count scheduler hops. The
 * one test that exercises the leash drives time explicitly instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun song(id: String) = Song(id, "T$id", "A$id", null)

class QualitySwapTest {

    private class Rig(val scope: TestScope) {
        val engine = FakeAudioEngine()

        /** Every silent engine the controller built for an audition. */
        val auditions = mutableListOf<FakeAudioEngine>()
        val repicks = mutableListOf<Pair<String, Int>>()

        /** videoId → URL the re-pick answers, or null for "the walk failed". Absent = same URL. */
        val repickTable = mutableMapOf<String, String?>()

        /**
         * What the audition engine answers the moment the controller plays the
         * candidate — the script the proof is judged against. Default: opens,
         * decodes, and reports the playing track's own 269s.
         */
        var proofKbps: Int? = 256
        var proofLengthMs: Long = 269_000
        var proofSilent = false
        var proofFailsWithError = false

        /** When true, the proof is left to the test to fire by hand. */
        var proofManual = false

        val player = PlayerController(CoroutineScope(StandardTestDispatcher(scope.testScheduler)), engine)

        init {
            QueueShuffle.setEnabled(false)
            AppSettings.setAutoplay(false)
            AppSettings.setAudioQuality(AudioQuality.LOSSLESS)
            player.resolveUrl = { videoId -> "https://test/$videoId" }
            player.resolveDispatcher = StandardTestDispatcher(scope.testScheduler)
            player.queueDispatcher = StandardTestDispatcher(scope.testScheduler)
            player.auditionEngine = {
                FakeAudioEngine().also { fake ->
                    auditions += fake
                    fake.onPlayHook = {
                        when {
                            proofManual -> {}
                            proofFailsWithError -> fake.onError?.invoke("403")
                            proofSilent -> {}
                            else -> {
                                fake.reportStats("mp4a", proofKbps, 44_100, 2)
                                fake.length(proofLengthMs)
                            }
                        }
                    }
                }
            }
            player.repickUrl = { videoId, maxKbps ->
                repicks += videoId to maxKbps
                repickTable[videoId]
            }
        }

        /** A track playing five seconds into its 269 — the swap's stage. */
        fun playTrack(id: String) {
            player.playFrom(listOf(song(id)), index = 0, source = PlaybackSourceType.SEARCH, sourceTitle = "S", sourceId = null)
            scope.advanceUntilIdle()
            engine.length(269_000)
            scope.advanceUntilIdle()
            engine.tick(5_000)
            scope.advanceUntilIdle()
        }

        /**
         * A quality change, and the whole swap chain it triggers, run to
         * quiescence — without advancing virtual time, so a proof the tests
         * script themselves is never overtaken by the audition's own leash
         * (the leash test advances the clock when it wants that).
         */
        fun swapTo(quality: AudioQuality) {
            player.setAudioQuality(quality)
            drain()
        }

        /**
         * Scheduler hops without a single tick of virtual time — for the
         * moments a test parks mid-audition: [advanceUntilIdle] would run the
         * audition's own 8-second leash and end the race the test is inside.
         */
        fun drain() {
            repeat(64) { scope.runCurrent() }
        }
    }

    @AfterTest
    fun cleanUp() {
        NerdStats.clear()
    }

    @Test
    fun `a different candidate is auditioned, then swapped in at the playing position`() = runTest {
        val rig = Rig(this)
        with(rig) {
            playTrack("a")
            repickTable["a"] = "https://better/a"
            swapTo(AudioQuality.LOW)
            // One audition engine was built and asked to play, silently, from the top.
            assertEquals(1, auditions.size)
            assertEquals("https://better/a", auditions[0].playedUrls.single())
            assertTrue(auditions[0].volumes.contains(0))
            // The swap: the main engine plays the candidate from where the clock was.
            assertEquals("https://better/a", engine.playedUrls.last())
            assertEquals(listOf(5_000L), engine.playedStartMs)
            // The race ended with the swap, and the row is still the row.
            assertFalse(NerdStats.isQualityRacing("a"))
            assertEquals("a", player.state.value.song?.videoId)
        }
    }

    @Test
    fun `the same candidate is no swap at all`() = runTest {
        val rig = Rig(this)
        with(rig) {
            playTrack("a")
            // The re-pick answers with the very URL playing — MEDIUM, HIGH and
            // LOSSLESS share a ceiling here, so this is the common case.
            repickTable["a"] = "https://test/a"
            swapTo(AudioQuality.LOW)
            assertTrue(auditions.isEmpty())
            assertEquals(listOf("https://test/a"), engine.playedUrls)
            assertFalse(NerdStats.isQualityRacing("a"))
        }
    }

    @Test
    fun `a re-pick failure keeps the playing stream`() = runTest {
        val rig = Rig(this)
        with(rig) {
            playTrack("a")
            repickTable["a"] = null
            swapTo(AudioQuality.LOW)
            assertTrue(auditions.isEmpty())
            assertEquals(listOf("https://test/a"), engine.playedUrls)
        }
    }

    @Test
    fun `an engine error refuses the candidate`() = runTest {
        val rig = Rig(this)
        with(rig) {
            playTrack("a")
            repickTable["a"] = "https://better/a"
            rig.proofFailsWithError = true
            swapTo(AudioQuality.LOW)
            assertEquals(1, auditions.size)
            assertTrue(auditions[0].releasedEngine)
            assertEquals(listOf("https://test/a"), engine.playedUrls)
            assertFalse(NerdStats.isQualityRacing("a"))
        }
    }

    @Test
    fun `a different edit of the song is refused`() = runTest {
        val rig = Rig(this)
        with(rig) {
            playTrack("a") // 269s on the clock
            repickTable["a"] = "https://better/a"
            rig.proofLengthMs = 300_000 // 31s off: not this recording
            swapTo(AudioQuality.LOW)
            assertEquals(listOf("https://test/a"), engine.playedUrls)
            assertFalse(NerdStats.isQualityRacing("a"))
            assertTrue(auditions[0].releasedEngine)
        }
    }

    @Test
    fun `a silent candidate is dropped when the leash runs out`() = runTest {
        val rig = Rig(this)
        with(rig) {
            playTrack("a")
            repickTable["a"] = "https://better/a"
            rig.proofSilent = true
            swapTo(AudioQuality.LOW)
            // The audition awaited and said nothing; the leash is what ends it.
            scope.advanceTimeBy(AUDITION_LEASH_MS + 1)
            scope.advanceUntilIdle()
            assertEquals(listOf("https://test/a"), engine.playedUrls)
            assertFalse(NerdStats.isQualityRacing("a"))
            assertTrue(auditions[0].releasedEngine)
        }
    }

    @Test
    fun `a paused track is not swapped`() = runTest {
        val rig = Rig(this)
        with(rig) {
            playTrack("a")
            engine.pause()
            scope.advanceUntilIdle()
            repickTable["a"] = "https://better/a"
            swapTo(AudioQuality.LOW)
            assertTrue(repicks.isEmpty())
            assertTrue(auditions.isEmpty())
        }
    }

    @Test
    fun `too little remaining is not worth the cut`() = runTest {
        val rig = Rig(this)
        with(rig) {
            playTrack("a")
            engine.length(14_000)
            engine.tick(5_000) // 9s left
            scope.advanceUntilIdle()
            repickTable["a"] = "https://better/a"
            swapTo(AudioQuality.LOW)
            assertTrue(repicks.isEmpty())
        }
    }

    @Test
    fun `the race lights when the audition starts and ends with it`() = runTest {
        val rig = Rig(this)
        with(rig) {
            playTrack("a")
            repickTable["a"] = "https://better/a"
            rig.proofManual = true
            swapTo(AudioQuality.LOW)
            drain()
            // The candidate is on the silent engine and the audition is open.
            assertTrue(NerdStats.isQualityRacing("a"))
            auditions[0].reportStats("mp4a", 256, 44_100, 2)
            auditions[0].length(269_000)
            drain()
            assertFalse(NerdStats.isQualityRacing("a"))
            assertEquals("https://better/a", engine.playedUrls.last())
        }
    }

    @Test
    fun `a row change mid-audition abandons the swap`() = runTest {
        val rig = Rig(this)
        with(rig) {
            playTrack("a")
            repickTable["a"] = "https://better/a"
            rig.proofManual = true
            swapTo(AudioQuality.LOW)
            drain()
            assertTrue(auditions.isNotEmpty())
            assertTrue(NerdStats.isQualityRacing("a"))
            // The queue runs out: the row the swap was for ends its playback.
            engine.finish()
            drain()
            // Even a late proof cannot land on the silence that replaced it —
            // and the audition's engine went back with the attempt.
            auditions[0].reportStats("mp4a", 256, 44_100, 2)
            auditions[0].length(269_000)
            drain()
            assertEquals(listOf("https://test/a"), engine.playedUrls)
            assertFalse(NerdStats.isQualityRacing("a"))
            assertTrue(auditions[0].releasedEngine)
        }
    }

    @Test
    fun `the swap re-arms for a second change`() = runTest {
        val rig = Rig(this)
        with(rig) {
            playTrack("a")
            repickTable["a"] = "https://better/a"
            swapTo(AudioQuality.LOW)
            assertEquals("https://better/a", engine.playedUrls.last())
            // And back up: the re-pick now names the original under the new ceiling.
            repickTable["a"] = "https://test/a"
            swapTo(AudioQuality.HIGH)
            assertEquals(2, auditions.size)
            assertEquals("https://test/a", engine.playedUrls.last())
        }
    }

    private companion object {
        /** Mirrors the controller's `AUDITION_TIMEOUT_MS` — see its own KDoc. */
        const val AUDITION_LEASH_MS = 8_000L
    }
}
