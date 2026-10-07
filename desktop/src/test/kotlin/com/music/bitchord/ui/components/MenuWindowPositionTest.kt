package com.music.bitchord.ui.components

import androidx.compose.ui.unit.IntOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a right-click's menu opens, as a truth table.
 *
 * [menuWindowPosition] receives the click already in window coordinates —
 * the provider adds the anchor row's own offset before calling — and answers
 * the position the popup layer will take literally. The rows pin the two
 * behaviours the surface owes the hand: the menu arrives *at the click*, and
 * it arrives *whole* when the click is close enough to an edge that the menu
 * as sized would hang off the window.
 *
 * The table is also the regression net for the bug this rule replaced: the
 * old arithmetic subtracted the anchor's offset instead of adding it, so a
 * click anywhere below the window's first few dozen pixels produced a
 * negative answer on the y axis and the clamp's floor won — every menu in
 * the app opened at the top-left corner.
 */
class MenuWindowPositionTest {

    @Test
    fun `a click in the open opens the menu at the click`() {
        assertEquals(
            IntOffset(600, 400),
            menuWindowPosition(
                clickX = 600, clickY = 400,
                windowWidth = 1180, windowHeight = 780,
                menuWidth = 180, menuHeight = 250,
            ),
        )
    }

    @Test
    fun `a click in the top-left corner keeps its corner`() {
        assertEquals(
            IntOffset(0, 0),
            menuWindowPosition(
                clickX = 0, clickY = 0,
                windowWidth = 1180, windowHeight = 780,
                menuWidth = 180, menuHeight = 250,
            ),
        )
    }

    @Test
    fun `a menu that would hang off the right edge is pulled inside`() {
        // 1100 + 180 = 1280 past the window's 1180; the menu's right edge lands
        // on the window's right edge exactly.
        assertEquals(
            IntOffset(1000, 400),
            menuWindowPosition(
                clickX = 1100, clickY = 400,
                windowWidth = 1180, windowHeight = 780,
                menuWidth = 180, menuHeight = 250,
            ),
        )
    }

    @Test
    fun `a menu that would hang off the bottom edge is lifted inside`() {
        // The row that used to lose to the clamp's floor: rows near the window's
        // bottom are where right-clicks actually happen, the list being what it is.
        assertEquals(
            IntOffset(600, 530),
            menuWindowPosition(
                clickX = 600, clickY = 700,
                windowWidth = 1180, windowHeight = 780,
                menuWidth = 180, menuHeight = 250,
            ),
        )
    }

    @Test
    fun `a menu wider than the window clamps to the origin instead of throwing`() {
        assertEquals(
            IntOffset(0, 400),
            menuWindowPosition(
                clickX = 500, clickY = 400,
                windowWidth = 100, windowHeight = 780,
                menuWidth = 180, menuHeight = 250,
            ),
        )
    }
}
