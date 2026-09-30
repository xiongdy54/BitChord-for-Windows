// Ported from app/src/test/java/com/music/bitchord/QueueHistoryTest.kt — package and assertion
// imports only; every expected value is the original's.
//
// The original's two `LastPlayed.window` cases are not carried over here: LastPlayed.kt is
// Android SharedPreferences persistence, outside this slice, so its windowing stays untested
// until a desktop equivalent exists.
package com.music.bitchord.playback

import com.music.bitchord.playback.MAX_QUEUE_HISTORY
import com.music.bitchord.playback.queueHistoryTrimCount
import com.music.bitchord.playback.queueStartingAt
import com.music.bitchord.playback.skippedByQueueJump
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueHistoryTest {

    @Test
    fun `history retains at most twenty five songs`() {
        assertEquals(0, queueHistoryTrimCount(MAX_QUEUE_HISTORY))
        assertEquals(1, queueHistoryTrimCount(MAX_QUEUE_HISTORY + 1))
        assertEquals(75, queueHistoryTrimCount(100))
    }

    @Test
    fun `a direct forward choice removes only bypassed songs`() {
        assertEquals(3..6, skippedByQueueJump(currentIndex = 2, targetIndex = 7))
    }

    @Test
    fun `next and previous navigation preserve the queue`() {
        assertNull(skippedByQueueJump(currentIndex = 2, targetIndex = 3))
        assertNull(skippedByQueueJump(currentIndex = 7, targetIndex = 2))
    }

    @Test
    fun `starting in the middle does not turn earlier unplayed rows into history`() {
        assertEquals(listOf("c", "d"), queueStartingAt(listOf("a", "b", "c", "d"), 2))
    }
}
