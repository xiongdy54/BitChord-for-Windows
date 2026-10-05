package com.music.bitchord.ui

import com.music.bitchord.ui.shell.WindowGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The window-geometry codec. Every malformed input reads as "no remembered
 * placement", because a hand-edited properties file must never be able to
 * produce a zero-width window — the parse either yields a sane placement or
 * nothing at all.
 */
class WindowGeometryTest {

    @Test
    fun `encodes and parses back`() {
        val geometry = WindowGeometry(1180, 780, false)
        assertEquals("1180,780,0", geometry.encode())
        assertEquals(geometry, WindowGeometry.parseOrNull(geometry.encode()))
    }

    @Test
    fun `maximisation rides along as its own flag`() {
        val parsed = WindowGeometry.parseOrNull("1920,1040,1")
        assertEquals(WindowGeometry(1920, 1040, true), parsed)
    }

    @Test
    fun `absent and empty read as nothing`() {
        assertNull(WindowGeometry.parseOrNull(null))
        assertNull(WindowGeometry.parseOrNull(""))
        assertNull(WindowGeometry.parseOrNull("   "))
    }

    @Test
    fun `malformed values read as nothing`() {
        assertNull(WindowGeometry.parseOrNull("1180"))
        assertNull(WindowGeometry.parseOrNull("1180,780"))
        assertNull(WindowGeometry.parseOrNull("1180,780,2"))
        assertNull(WindowGeometry.parseOrNull("1180,780,true"))
        assertNull(WindowGeometry.parseOrNull("a,b,c"))
        assertNull(WindowGeometry.parseOrNull("0,780,0"))
        assertNull(WindowGeometry.parseOrNull("1180,-1,0"))
    }

    @Test
    fun `whitespace around fields is tolerated`() {
        assertEquals(WindowGeometry(1180, 780, false), WindowGeometry.parseOrNull(" 1180 , 780 , 0 "))
    }

    @Test
    fun `a maximised window still remembers real bounds`() {
        // The save path stores the windowState size, which for a maximised
        // window is the bounds it will return to — never a degenerate zero.
        val geometry = WindowGeometry(1180, 780, maximized = true)
        val encoded = geometry.encode()
        assertTrue(encoded.endsWith(",1"))
        assertEquals(geometry, WindowGeometry.parseOrNull(encoded))
    }
}
