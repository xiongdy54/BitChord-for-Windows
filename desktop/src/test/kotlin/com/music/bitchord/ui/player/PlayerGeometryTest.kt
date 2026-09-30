package com.music.bitchord.ui.player

import androidx.compose.ui.unit.dp
import com.music.bitchord.playback.MAX_QUEUE_HISTORY
import com.music.bitchord.playback.QueueTimeline
import com.music.bitchord.playback.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Numbers the port is not allowed to invent. Each is the Android app's own; a drift
 * here means a ported file was rewritten rather than carried over — exactly the
 * failure mode decision 3 exists to prevent.
 *
 * The app file and line cited in each group is where that number is defined
 * upstream; `PlayerGeometryTest` is why those declarations are `internal` there
 * rather than the app's `private`.
 */
class PlayerGeometryTest {

    @Test
    fun `transport sizes match the app's`() {
        // PlayerControls.kt:434, :699, :700, :706, :708, :724, :737.
        assertEquals(32.dp, VOLUME_ROW_HEIGHT)
        assertEquals(44.dp, BOTTOM_ACTION_SIZE)
        assertEquals(53.dp, PLAYER_SKIP_ICON_SIZE)
        assertEquals(53.dp, PLAYER_SKIP_TOUCH_SIZE)
        assertEquals(0.85f, PLAYER_SKIP_HEIGHT_SCALE)
        assertEquals(52.dp, PILL_SEGMENT_WIDTH_TRIPLE)
        assertEquals(24.dp, PILL_ICON_SIZE)
    }

    @Test
    fun `marquee motion matches the app's`() {
        // PlayerControls.kt:1132, :1135, :1138.
        assertEquals(26f, MARQUEE_DP_PER_SEC)
        assertEquals(48.dp, MARQUEE_GAP)
        assertEquals(5_000L, MARQUEE_REST_MS)
    }

    @Test
    fun `the landscape rule is the original's, not a desktop one`() {
        // NowPlayingScreen.kt:408-409 — wider than it is tall, and at least
        // :316's LANDSCAPE_PLAYER_MIN_WIDTH (560dp) across. The boundary is
        // ">=", so 560dp is in and 559dp is out.
        assertEquals(true, landscapePlayerAvailable(1180.dp, 780.dp))
        assertEquals(false, landscapePlayerAvailable(780.dp, 1180.dp))
        assertEquals(false, landscapePlayerAvailable(559.dp, 100.dp))
        assertEquals(true, landscapePlayerAvailable(560.dp, 100.dp))
    }

    @Test
    fun `the player column keeps the app's own width and gutter`() {
        // NowPlayingScreen.kt:291, :284, :302.
        assertEquals(560.dp, PLAYER_MAX_WIDTH)
        assertEquals(30.dp, PLAYER_GUTTER)
        assertEquals(700.dp, TABLET_PLAYER_MIN_WIDTH)
    }

    @Test
    fun `the gesture gate and the queue constants are the app's own numbers`() {
        // PlayerControls.kt:125 — the shuffle glyph's tap window.
        assertEquals(400L, SHUFFLE_TAP_WINDOW_MS)
        // QueueHistory.kt:5, PlaybackService.kt:157 via QueueTimeline:37.
        assertEquals(25, MAX_QUEUE_HISTORY)
        assertEquals(10_000L, QueueTimeline.BACK_RESTARTS_AFTER_MS)
        // The three repeat states the ported PlayerControls comparisons use.
        assertEquals(0, RepeatMode.OFF)
        assertEquals(2, RepeatMode.ALL)
    }
}
