package com.music.bitchord.ui.shell

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Apple Music shell's measurements, in one place and pinned by
 * [com.music.bitchord.ui.DesktopLayoutTest] — the numbers a redesign is most
 * tempted to nudge, and the ones every page's composition quietly leans on.
 */

/** The two easings the shell's own motion runs on: in fast, out gentle. */
val EASE_OUT = CubicBezierEasing(0f, 0f, 0.2f, 1f)
val EASE_IN = CubicBezierEasing(0.4f, 0f, 1f, 1f)

/** The sidebar's width when the window is wide enough to show it whole. */
val SIDEBAR_WIDTH = 230.dp

/** The sidebar's collapsed width — icons only, the way the app narrows on a small window. */
val SIDEBAR_RAIL_WIDTH = 56.dp

/** The toolbar's height. Deliberately the old top bar's height, so the ported pages' rhythm survives. */
val TOOLBAR_HEIGHT = 52.dp

/**
 * The now-playing display's width in the toolbar's centre cluster — wide
 * enough to read a title and an artist, fixed so the cluster's shape does
 * not breathe with the text. Steps down to [LCD_WIDTH_NARROW] under 840dp of
 * window, before the cluster can crowd the nav arrows.
 */
val LCD_WIDTH = 320.dp
val LCD_WIDTH_NARROW = 220.dp

/** The gap between the cluster's three parts: transport · display · volume. */
val CLUSTER_GAP = 10.dp

/**
 * The page transition's two halves, in milliseconds — enter longer than exit,
 * enter easing out and exit easing in, the asymmetry that makes a swap read
 * as motion. Deliberately under the 300ms a slow transition crosses into;
 * navigation is frequent, and the point is orientation, not theatre.
 */
const val PAGE_TRANSITION_ENTER_MS = 220
const val PAGE_TRANSITION_EXIT_MS = 140

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
