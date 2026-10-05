package com.music.bitchord.ui

import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.components.heroCardWidth
import com.music.bitchord.ui.shell.CONTENT_MAX_WIDTH
import com.music.bitchord.ui.shell.SIDEBAR_BREAKPOINT
import com.music.bitchord.ui.shell.SIDEBAR_RAIL_WIDTH
import com.music.bitchord.ui.shell.SIDEBAR_WIDTH
import com.music.bitchord.ui.shell.SidebarMode
import com.music.bitchord.ui.shell.TOOLBAR_HEIGHT
import com.music.bitchord.ui.shell.sidebarMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The geometry the desktop layout is allowed to change, and the geometry it is
 * not.
 *
 * The card numbers are the Android app's own (ui/components/Common.kt); if a
 * ported file ever drifts from them this fails, because the point of the port
 * is that a card is the same size on both. The shell numbers are the Apple
 * Music layout's, and they are pinned for the opposite reason: this layout is
 * desktop-native, so its own proportions are the contract.
 */
class DesktopLayoutTest {

    @Test
    fun `the shell's measurements are the ones the design names`() {
        assertEquals(230.dp, SIDEBAR_WIDTH)
        assertEquals(56.dp, SIDEBAR_RAIL_WIDTH)
        assertEquals(52.dp, TOOLBAR_HEIGHT)
        assertEquals(760.dp, SIDEBAR_BREAKPOINT)
        assertEquals(1280.dp, CONTENT_MAX_WIDTH)
    }

    @Test
    fun `the sidebar collapses under its breakpoint and stands whole above it`() {
        assertEquals(SidebarMode.RAIL, sidebarMode(720.dp))
        assertEquals(SidebarMode.FULL, sidebarMode(760.dp))
        assertEquals(SidebarMode.FULL, sidebarMode(1920.dp))
    }

    @Test
    fun `card geometry still matches the app's`() {
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
