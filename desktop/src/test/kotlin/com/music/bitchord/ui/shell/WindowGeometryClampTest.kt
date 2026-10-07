package com.music.bitchord.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The work-area clamp, as a truth table.
 *
 * [WindowGeometry.clampedInto] is the last gate between a session's window
 * shape and the geometry file, and the shapes it exists to refuse are the
 * ones the pipeline upstream of it actually produces: the screen's own bounds
 * written back while the frame was maximised or fullscreen, and anything a
 * drag on an undecorated frame was allowed to build. Restored unclamped, such
 * a shape opens as a centred window taller than the work area — its toolbar,
 * the title bar, above the screen's top, where no click can reach it.
 *
 * The units are the platform's own for window bounds: physical pixels, the
 * same numbers `WindowState` reports and this file stores.
 */
class WindowGeometryClampTest {

    private val workWidth = 1920
    private val workHeight = 1008

    @Test
    fun `a shape inside the work area passes through untouched`() {
        assertEquals(
            WindowGeometry(1180, 780, maximized = false),
            WindowGeometry(1180, 780, maximized = false).clampedInto(workWidth, workHeight),
        )
    }

    @Test
    fun `exactly the work area is not a violation`() {
        // A maximised frame's bounds are the work area's; a restore from that
        // pair must not shrink or nudge it.
        assertEquals(
            WindowGeometry(workWidth, workHeight, maximized = false),
            WindowGeometry(workWidth, workHeight, maximized = false).clampedInto(workWidth, workHeight),
        )
    }

    @Test
    fun `the screen's full height, written back over a fullscreen session, is refused`() {
        // The poison this clamp exists for: 1920x1080 is the whole screen, the
        // work area is what is left under the taskbar, and a centred frame of
        // that height would put its title bar 36 pixels above the screen's top.
        assertEquals(
            WindowGeometry(1920, workHeight, maximized = false),
            WindowGeometry(1920, 1080, maximized = false).clampedInto(workWidth, workHeight),
        )
    }

    @Test
    fun `a drag taller than the screen loses only the overshoot`() {
        assertEquals(
            WindowGeometry(1400, workHeight, maximized = false),
            WindowGeometry(1400, 1500, maximized = false).clampedInto(workWidth, workHeight),
        )
    }

    @Test
    fun `both axes clamp independently`() {
        assertEquals(
            WindowGeometry(800, 500, maximized = false),
            WindowGeometry(2000, 1500, maximized = false).clampedInto(800, 500),
        )
    }

    @Test
    fun `the maximised flag rides along untouched`() {
        // Maximisation is a placement, not a size; clamping the recorded size
        // must not be able to turn a maximised session back into a floating one.
        assertEquals(
            WindowGeometry(1920, workHeight, maximized = true),
            WindowGeometry(1920, 1080, maximized = true).clampedInto(workWidth, workHeight),
        )
    }

    @Test
    fun `a degenerate work area degrades to a one-pixel window, not an exception`() {
        // coerceIn throws when the range is empty, and a config that answers a
        // zero work area is exactly the kind of answer a clamp must survive.
        assertEquals(
            WindowGeometry(1, 1, maximized = false),
            WindowGeometry(1180, 780, maximized = false).clampedInto(0, 0),
        )
    }
}
