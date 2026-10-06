package com.music.bitchord.ui.shell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Apple Music shell's measurements, in one place and pinned by
 * [com.music.bitchord.ui.DesktopLayoutTest] — the numbers a redesign is most
 * tempted to nudge, and the ones every page's composition quietly leans on.
 */

/** The sidebar's width when the window is wide enough to show it whole. */
val SIDEBAR_WIDTH = 230.dp

/** The sidebar's collapsed width — icons only, the way the app narrows on a small window. */
val SIDEBAR_RAIL_WIDTH = 56.dp

/** The toolbar's height. Deliberately the old top bar's height, so the ported pages' rhythm survives. */
val TOOLBAR_HEIGHT = 52.dp

/** Under this window width the sidebar collapses to its icon rail. */
val SIDEBAR_BREAKPOINT = 760.dp

/**
 * How wide the page column is allowed to get.
 *
 * The phone layout capped at 1080dp with no sidebar beside it; the sidebar takes
 * [SIDEBAR_WIDTH] off the window, so the cap grows to keep the shelves' reach
 * roughly what it was — the point is still that past this the margins grow, not
 * the layout.
 */
val CONTENT_MAX_WIDTH = 1280.dp

/** Where page content starts below the toolbar — the old bar's gap, minus the bar it cleared. */
val PAGE_TOP_GUTTER = 12.dp

/** Which shape the sidebar takes at a given window width. */
enum class SidebarMode { FULL, RAIL }

fun sidebarMode(windowWidth: Dp): SidebarMode =
    if (windowWidth >= SIDEBAR_BREAKPOINT) SidebarMode.FULL else SidebarMode.RAIL
