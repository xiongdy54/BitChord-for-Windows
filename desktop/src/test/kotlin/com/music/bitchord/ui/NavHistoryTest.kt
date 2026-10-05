package com.music.bitchord.ui

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.ui.shell.Destination
import com.music.bitchord.ui.shell.NavHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The back/forward machinery, table-tested — it is the one piece of the shell
 * with real rules, and every navigation in the app (sidebar rows, cards,
 * toolbar arrows, Alt+arrows) funnels through it.
 */
class NavHistoryTest {

    private val home = Destination.Home
    private val search = Destination.Search
    private val explore = Destination.Explore
    private fun detail(id: String) = Destination.Detail(BrowseType.ALBUM, id, "T$id")

    @Test
    fun `starts on home with nowhere to go`() {
        val history = NavHistory()
        assertEquals(home, history.current)
        assertFalse(history.canGoBack)
        assertFalse(history.canGoForward)
    }

    @Test
    fun `opening walks forward and back returns`() {
        val history = NavHistory()
        history.open(search)
        history.open(explore)
        assertEquals(explore, history.current)
        assertTrue(history.canGoBack)
        history.back()
        assertEquals(search, history.current)
        history.back()
        assertEquals(home, history.current)
        assertFalse(history.canGoBack)
        assertTrue(history.canGoForward)
        history.forward()
        assertEquals(search, history.current)
    }

    @Test
    fun `opening from the middle truncates the forward tail`() {
        val history = NavHistory()
        history.open(search)
        history.open(explore)
        history.back()
        assertEquals(listOf(explore), history.forwardEntries())
        history.open(detail("a"))
        assertEquals(detail("a"), history.current)
        assertFalse(history.canGoForward)
        history.back()
        assertEquals(search, history.current)
    }

    @Test
    fun `opening the current destination changes nothing`() {
        val history = NavHistory()
        history.open(search)
        history.open(search)
        assertEquals(search, history.current)
        assertTrue(history.canGoBack)
        assertFalse(history.canGoForward)
        assertEquals(listOf(home), history.backEntries())
    }

    @Test
    fun `opening the entry behind the cursor is a fork, not a back`() {
        // Browser semantics: re-opening where you just were replaces "going
        // back", so the forward tail still dies and back from there reaches
        // home rather than looping in place.
        val history = NavHistory()
        history.open(search)
        history.back()
        history.open(search)
        assertEquals(search, history.current)
        assertFalse(history.canGoForward)
        history.back()
        assertEquals(home, history.current)
    }

    @Test
    fun `distinct detail pages with the same shape are distinct destinations`() {
        val history = NavHistory()
        history.open(detail("a"))
        history.open(detail("b"))
        assertEquals(detail("b"), history.current)
        history.back()
        assertEquals(detail("a"), history.current)
    }

    @Test
    fun `back and forward at the ends report the miss`() {
        val history = NavHistory()
        assertFalse(history.back())
        assertFalse(history.forward())
        history.open(search)
        assertTrue(history.back())
        assertFalse(history.back())
        assertTrue(history.forward())
        assertFalse(history.forward())
    }
}
