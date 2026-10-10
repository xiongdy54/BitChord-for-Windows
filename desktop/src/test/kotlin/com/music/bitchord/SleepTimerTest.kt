package com.music.bitchord

import com.music.bitchord.data.SystemClock
import com.music.bitchord.desktop.playback.SleepTimer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sleep timer's state machine, against its own word: a deadline, a chosen
 * preset and the after-this-song flag are three faces of one "armed" idea, and
 * every entry point has to leave exactly the face it names behind.
 *
 * The maths is read off the real monotonic clock — the timer has no clock seam
 * upstream either (that is the point of `elapsedRealtime`), and a test that
 * asks for "between 14 and 15 minutes left" off a clock that cannot jump is
 * as exact as the state it pins.
 */
class SleepTimerTest {

    @AfterTest
    fun cleanUp() {
        SleepTimer.cancel()
    }

    @Test
    fun `start arms a deadline one preset out and clears the after-track flag`() {
        SleepTimer.startAfterTrack()
        val before = SystemClock.elapsedRealtime()
        SleepTimer.start(30)
        val after = SystemClock.elapsedRealtime()
        assertEquals(30, SleepTimer.minutes.value)
        assertFalse(SleepTimer.afterTrack.value)
        assertTrue(SleepTimer.isRunning)
        val deadline = SleepTimer.deadline.value
        // 30 minutes out of a clock read somewhere inside [before, after]:
        // no fewer than 30 from `before`, no more than 30 from `after`.
        assertTrue(deadline!! - before >= 30 * 60_000L)
        assertTrue(deadline - after <= 30 * 60_000L)
    }

    @Test
    fun `startAfterTrack spends the deadline and the preset`() {
        SleepTimer.start(30)
        SleepTimer.startAfterTrack()
        assertNull(SleepTimer.deadline.value)
        assertNull(SleepTimer.minutes.value)
        assertTrue(SleepTimer.afterTrack.value)
        assertTrue(SleepTimer.isRunning)
    }

    @Test
    fun `cancel leaves nothing armed`() {
        SleepTimer.start(15)
        SleepTimer.cancel()
        assertNull(SleepTimer.deadline.value)
        assertNull(SleepTimer.minutes.value)
        assertFalse(SleepTimer.afterTrack.value)
        assertFalse(SleepTimer.isRunning)
        // Cancelling a timer that is not running is the same act as arming
        // nothing — the picker's turn-off row and the advance hook both call
        // it blind.
        SleepTimer.cancel()
        assertFalse(SleepTimer.isRunning)
    }

    @Test
    fun `remainingMs counts down to the deadline and floors at zero`() {
        SleepTimer.start(15)
        // A clock read microseconds after arming is a whisker under the whole
        // preset — "whole preset minus no more than a second" pins the maths
        // without racing the clock.
        val atStart = SleepTimer.remainingMs()!!
        assertTrue(atStart in (15 * 60_000L - 1_000)..(15 * 60_000L))
        // A deadline that has passed is not a negative countdown — the UI
        // formats it, and "-0:03" is a sentence nobody asked for.
        SleepTimer.deadline.value = SystemClock.elapsedRealtime() - 5_000
        assertEquals(0L, SleepTimer.remainingMs())
    }

    @Test
    fun `remainingMs is null when nothing is armed, including after this song`() {
        assertNull(SleepTimer.remainingMs())
        SleepTimer.startAfterTrack()
        assertNull(SleepTimer.remainingMs())
        assertTrue(SleepTimer.isRunning)
    }

    @Test
    fun `the presets are the ones the picker offers`() {
        assertEquals(listOf(15, 30, 45, 60), SleepTimer.PRESETS)
    }
}
