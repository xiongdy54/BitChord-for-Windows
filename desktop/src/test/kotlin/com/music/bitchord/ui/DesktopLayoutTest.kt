package com.music.bitchord.ui

import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.FLOATING_BAR_MAX_WIDTH
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.components.heroCardWidth
import com.music.bitchord.ui.shell.CONTENT_MAX_WIDTH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The geometry the desktop layout is allowed to change, and the geometry it is
 * not.
 *
 * The four numbers in [floating bar cap matches the app's] are the Android
 * app's own (ui/components/Common.kt); if a ported file ever drifts from them
 * this fails, because the point of the port is that a card is the same size on
 * both.
 */
class DesktopLayoutTest {

    @Test
    fun `content column is capped where the design says`() {
        assertEquals(1080, CONTENT_MAX_WIDTH)
    }

    @Test
    fun `floating bar cap matches the app's`() {
        assertEquals(440.dp, FLOATING_BAR_MAX_WIDTH)
        assertEquals(150.dp, SHELF_CARD_WIDTH)
        assertEquals(10.dp, PAGE_GUTTER)
    }

    @Test
    fun `hero cards stay under their ceiling at any window width`() {
        assertTrue(heroCardWidth(1920.dp) <= 320.dp)
        assertTrue(heroCardWidth(1180.dp) <= 320.dp)
        assertTrue(heroCardWidth(400.dp) < 320.dp)
    }
}
