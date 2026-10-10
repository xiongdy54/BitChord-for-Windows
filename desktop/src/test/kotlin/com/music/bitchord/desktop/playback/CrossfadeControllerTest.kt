package com.music.bitchord.desktop.playback

import com.music.bitchord.data.settings.AppSettings
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
 * The crossfade state machine, driven by hand.
 *
 * The controller is built directly against its seams — two fake engines, a
 * queue's worth of booleans and a virtual clock — and every transition is
 * walked tick by tick with [CrossfadeController.tickForTest], so the table
 * below pins the arithmetic (the arm window, the curve, the caps) without a
 * wall clock anywhere near it. The one thing this file does not see is
 * [PlayerController]: the seam the machine hands off across is the
 * PlayerControllerTest file's business; what is pinned here is that the
 * machine's side of every handoff, bail and branch happens exactly once and
 * at exactly the moment the design names.
 */
class CrossfadeControllerTest {

    /** The seams the machine reads, as one mutable world a test drives by hand. */
    private class World {
        val session = FakeAudioEngine()
        var standby: FakeAudioEngine? = null
        var playing = false
        var duration = 0L
        var has = false
        var repeatOne = false
        var next: String? = null
        var volume = 100
        var swapActive = false
        var virtualNow = 0L
        var handoffs = 0
        var handedOut: AudioEngine? = null
        var handedIn: AudioEngine? = null

        val controller: CrossfadeController = CrossfadeController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            active = { session },
            standby = { standby ?: FakeAudioEngine().also { standby = it } },
            onHandoff = { out, into ->
                handoffs++
                handedOut = out
                handedIn = into
                // The rebind the player's own handoff performs (and the tests
                // below depend on): the outgoing engine's advance is
                // forgotten, and its end of media reports to the machine.
                out.onFinished = { controller.onOutgoingTailEnded() }
            },
            sessionPlaying = { playing },
            durationMs = { duration },
            hasNext = { has },
            repeatOne = { repeatOne },
            nextVideoId = { next },
            userVolume = { volume },
            resolve = { videoId -> "https://resolved/$videoId" },
            headersFor = { emptyMap() },
            versionSwapActive = { swapActive },
            now = { virtualNow },
        )

        init {
            AppSettings.crossfadeSeconds.value = 6
        }

        /** A 180-second track at the user's full volume, playing, with a next row waiting. */
        fun playingTrackWithNext() {
            playing = true
            duration = 180_000L
            has = true
            next = "b"
            // The engine itself is audible, not just the snapshot's word for
            // it — the pause shadow reads both.
            session.isPlaying = true
        }

        /** The handoff's moment: the standby ready, the session at its fade point. */
        fun armAndHandOff() {
            playingTrackWithNext()
            session.tick(170_000) // remaining 10s = fade 6s + lead 4s
            controller.tickForTest()
            standby!!.length(200_000)
            session.tick(174_000) // remaining 6s = the fade point
            controller.tickForTest()
        }
    }

    @AfterTest
    fun resetGlobals() {
        AppSettings.crossfadeSeconds.value = 0
        SleepTimer.cancel()
    }

    // ---- arming ----------------------------------------------------------

    @Test
    fun `the arm window opens a fade plus its lead, and not before`() {
        val w = World()
        w.playingTrackWithNext()
        // One millisecond short of the window: nothing armed, no standby built.
        w.session.tick(169_999)
        w.controller.tickForTest()
        assertNull(w.standby)
        assertFalse(w.controller.isTransitioning())
        // The window's first millisecond: the standby exists, holds the next
        // row's stream, and holds it silently.
        w.session.tick(170_000)
        w.controller.tickForTest()
        val standby = w.standby
        assertTrue(standby != null)
        assertEquals(listOf("https://resolved/b"), standby!!.preparedUrls)
        assertEquals(emptyList(), standby.playedUrls)
        assertTrue(w.controller.isTransitioning())
        // The arm is not the fade: nothing has sounded, and the handoff has
        // not happened.
        assertEquals(0, w.handoffs)
        assertFalse(standby.isPlaying)
    }

    @Test
    fun `a fade is capped at a third of the track it serves`() {
        val w = World()
        w.playing = true
        w.duration = 15_000L // a third of which is 5s — the fade the 6s setting becomes
        w.has = true
        w.next = "b"
        // remaining 9_001 against a 5s fade + 4s lead: one short of the window.
        w.session.tick(5_999)
        w.controller.tickForTest()
        assertNull(w.standby)
        w.session.tick(6_000)
        w.controller.tickForTest()
        assertTrue(w.standby != null)
    }

    @Test
    fun `nothing is armed when a trigger condition fails`() {
        // Each row builds its own world so the guards are read fresh.
        fun worldWithout(condition: World.() -> Unit): World {
            val w = World()
            w.playingTrackWithNext()
            w.session.tick(175_000) // deep inside the arm window
            w.condition()
            w.controller.tickForTest()
            return w
        }
        // Not playing, a version swap in the air, no next row, no known
        // duration, repeat-one, and the setting at zero each refuse on their
        // own: no standby, no transition.
        listOf(
            "paused" to { w: World -> w.playing = false },
            "version swap" to { w: World -> w.swapActive = true },
            "no next" to { w: World -> w.has = false },
            "duration unknown" to { w: World -> w.duration = 0L },
            "repeat-one" to { w: World -> w.repeatOne = true },
            "setting off" to { w: World -> AppSettings.crossfadeSeconds.value = 0 },
        ).forEach { (name, refuse) ->
            val w = worldWithout(refuse)
            assertNull(w.standby, name)
            assertFalse(w.controller.isTransitioning(), name)
        }
    }

    @Test
    fun `an armed transition that never becomes ready times out and lets the queue move on`() {
        val w = World()
        w.playingTrackWithNext()
        w.session.tick(170_000)
        w.controller.tickForTest()
        val standby = w.standby!!
        // No length ever reported. Past ARM_TIMEOUT_MS the arm is abandoned —
        // a missed crossfade, not a broken one — and the standby is put back
        // in the drawer.
        w.virtualNow += 12_001
        w.controller.tickForTest()
        assertFalse(w.controller.isTransitioning())
        assertFalse(standby.isPlaying)
        // And the machine still works: the same position re-arms.
        w.controller.tickForTest()
        assertTrue(w.controller.isTransitioning())
    }

    @Test
    fun `an expired arm whose standby did become ready still hands off`() {
        val w = World()
        w.playingTrackWithNext()
        w.session.tick(170_000)
        w.controller.tickForTest()
        w.standby!!.length(200_000)
        w.virtualNow += 13_000
        w.session.tick(174_000)
        w.controller.tickForTest()
        assertEquals(1, w.handoffs)
    }

    // ---- the handoff -------------------------------------------------------

    @Test
    fun `the handoff fires once, on the first sound, and moves the pair`() {
        val w = World()
        w.playingTrackWithNext()
        w.session.tick(170_000)
        w.controller.tickForTest()
        val standby = w.standby!!
        standby.length(200_000)
        w.session.tick(173_000) // remaining 7s: not at the fade point yet
        w.controller.tickForTest()
        assertEquals(0, w.handoffs)
        assertFalse(standby.isPlaying)
        w.session.tick(174_000)
        w.controller.tickForTest()
        assertEquals(1, w.handoffs)
        assertSame(w.session, w.handedOut)
        assertSame(standby, w.handedIn)
        // The incoming track is audible from this moment, and further ticks
        // of the same transition do not hand off again.
        assertTrue(standby.isPlaying)
        w.session.tick(174_500)
        w.controller.tickForTest()
        assertEquals(1, w.handoffs)
        assertTrue(w.controller.isTransitioning())
    }

    @Test
    fun `a skip before the handoff costs only the standby`() {
        val w = World()
        w.playingTrackWithNext()
        w.session.tick(170_000)
        w.controller.tickForTest()
        val standby = w.standby!!
        w.controller.onSkipRequested()
        assertFalse(w.controller.isTransitioning())
        // Never sounded, and put away stopped.
        assertEquals(emptyList(), standby.playedUrls)
        assertFalse(standby.isPlaying)
        // And the session keeps its own volume: the disarm's finish hands it
        // back at the listener's setting — the only write it was ever given.
        assertEquals(listOf(100), w.session.volumes)
    }

    // ---- the curve ---------------------------------------------------------

    @Test
    fun `the equal-power pair meets its endpoints and never dips`() {
        val w = World()
        w.armAndHandOff()
        val standby = w.standby!!
        val fade = listOf(0L, 1_500L, 3_000L, 4_500L, 6_000L)
        fade.forEach { ms -> standby.tick(ms); w.controller.tickForTest() }
        // sin²+cos²=1, at the listener's full 100: the outgoing falls as the
        // incoming rises, they cross at 71 (the equal-power midpoint, not a
        // 50/50 dip), and the endpoints are exact. The leading zeros are the
        // arm's mute and the handoff's re-assert; the trailing 100s are the
        // retire/reset writes of [finish], not the curve.
        assertEquals(listOf(100, 92, 71, 38, 0, 100), w.session.volumes)
        assertEquals(listOf(0, 0, 0, 38, 71, 92, 100, 100), standby.volumes)
        assertFalse(w.controller.isTransitioning())
    }

    @Test
    fun `a long fade tightens against a short incoming track`() {
        val w = World()
        w.armAndHandOff()
        val standby = w.standby!!
        // The incoming track is 9s long: a third of which caps the 6s fade at 3s.
        standby.length(9_000)
        standby.tick(2_999)
        w.controller.tickForTest()
        assertTrue(w.controller.isTransitioning())
        standby.tick(3_000)
        w.controller.tickForTest()
        assertFalse(w.controller.isTransitioning())
    }

    @Test
    fun `a pause parks the fade where it stands and resuming picks it up`() {
        val w = World()
        w.armAndHandOff()
        val standby = w.standby!!
        standby.tick(1_500)
        w.controller.tickForTest()
        assertEquals(92, w.session.volumes.last())
        // The listener pauses; the tick's shadow takes the outgoing engine
        // with it — one half of a blend never carries on alone.
        w.playing = false
        standby.pause()
        w.controller.tickForTest()
        assertFalse(w.session.isPlaying)
        // Time does not move while paused, so the fade does not either: the
        // same gains, rewritten.
        w.controller.tickForTest()
        assertEquals(92, w.session.volumes.last())
        assertEquals(38, standby.volumes.last())
        // Resuming picks the blend back up, shadow and all.
        w.playing = true
        standby.resume()
        w.controller.tickForTest()
        assertTrue(w.session.isPlaying)
        standby.tick(3_000)
        w.controller.tickForTest()
        assertEquals(71, w.session.volumes.last())
        assertEquals(71, standby.volumes.last())
    }

    // ---- interruption ------------------------------------------------------

    @Test
    fun `a bail ramps the tail away without a pop`() {
        val w = World()
        w.armAndHandOff()
        val standby = w.standby!!
        standby.tick(3_000)
        w.controller.tickForTest()
        assertEquals(71, w.session.volumes.last())
        w.controller.onSkipRequested()
        // The track the listener asked for — the incoming one — goes straight
        // to full; the interruption belongs to the outgoing track alone.
        assertEquals(100, standby.volumes.last())
        assertTrue(w.controller.isTransitioning())
        // Fifteen-millisecond steps across the 120ms ramp: one direction
        // only, from wherever the blend was to silence, then the drawer. The
        // eighth step lands exactly on BAIL_MS, where the ramp ends and the
        // machine retires the tail.
        repeat(8) { w.virtualNow += 15; w.controller.tickForTest() }
        assertEquals(
            listOf(71, 69, 65, 59, 50, 39, 27, 14, 100),
            w.session.volumes,
        )
        assertFalse(w.controller.isTransitioning())
        // Idle again — and the track under the needle is still inside its arm
        // window, so the next tick watches anew: the skip cost the blend, not
        // the watch.
        w.virtualNow += 15
        w.controller.tickForTest()
        assertTrue(w.controller.isTransitioning())
    }

    @Test
    fun `the outgoing engine's own end is the fade's completion, and nothing more`() {
        val w = World()
        w.armAndHandOff()
        val standby = w.standby!!
        // The tail runs out early — before the fade's own span.
        w.session.finish()
        assertEquals(1, w.handoffs) // its end is not another handoff
        w.controller.tickForTest()
        assertFalse(w.controller.isTransitioning())
        assertTrue(standby.isPlaying) // the incoming track stands alone at once
        // The blend is over and nothing is playing now; a second report of
        // the same end — a test's double finish, libvlc's tail events — lands
        // on an idle machine and moves nothing.
        w.playing = false
        w.session.finish()
        w.controller.tickForTest()
        assertFalse(w.controller.isTransitioning())
        assertEquals(1, w.handoffs)
    }

    @Test
    fun `the setting switched off mid-blend finishes the fade instead of stranding it`() {
        val w = World()
        w.armAndHandOff()
        val standby = w.standby!!
        standby.tick(1_500)
        w.controller.tickForTest()
        AppSettings.crossfadeSeconds.value = 0
        w.controller.tickForTest()
        assertFalse(w.controller.isTransitioning())
        assertEquals(100, standby.volumes.last())
    }

    // ---- sleep fade --------------------------------------------------------

    @Test
    fun `the sleep timer fades the track out on its own and pauses it`() {
        val w = World()
        w.playingTrackWithNext()
        SleepTimer.afterTrack.value = true
        w.session.tick(174_500) // remaining 5.5s, inside the 6s fade
        w.controller.tickForTest()
        // Nothing is armed: there is no next track to blend into, and no
        // second decoder worth spending on a fade to silence.
        assertNull(w.standby)
        assertTrue(w.controller.isTransitioning())
        w.controller.tickForTest()
        assertEquals(100, w.session.volumes.last())
        w.session.tick(177_500) // 3s into the 6s span: the equal-power midpoint
        w.controller.tickForTest()
        assertEquals(71, w.session.volumes.last())
        // The track's own end arrives as the fade's completion: the advance
        // asks, the machine consumes it, and the fade pauses the player and
        // retires the timer.
        w.session.finish()
        assertTrue(w.controller.onSessionEnded())
        w.controller.tickForTest()
        assertFalse(w.controller.isTransitioning())
        assertFalse(w.session.isPlaying)
        assertFalse(SleepTimer.afterTrack.value)
        assertEquals(100, w.session.volumes.last())
    }

    @Test
    fun `a sleep fade whose timer is disarmed mid-fade hands the track back`() {
        val w = World()
        w.playingTrackWithNext()
        SleepTimer.afterTrack.value = true
        w.session.tick(174_500)
        w.controller.tickForTest()
        w.session.tick(177_500)
        w.controller.tickForTest()
        SleepTimer.afterTrack.value = false
        w.controller.tickForTest()
        assertFalse(w.controller.isTransitioning())
        assertEquals(100, w.session.volumes.last())
    }

    @Test
    fun `an engine end outside a sleep fade is somebody's track ending`() {
        val w = World()
        assertFalse(w.controller.onSessionEnded())
    }
}
