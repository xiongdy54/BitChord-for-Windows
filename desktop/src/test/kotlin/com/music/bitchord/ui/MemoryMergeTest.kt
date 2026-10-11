package com.music.bitchord.ui

import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.data.settings.merged
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The memory slice's pure arithmetic: the home feed's shelf merge (the
 * prepend-and-dedupe the recently-played lead shelf rides), the search
 * history's record rule, and the update check's version comparison.
 */
class MemoryMergeTest {

    private fun shelf(title: String) = HomeShelf(title, listOf(ShelfItem("t", "s", null, null, null)))

    // ── Home shelves ─────────────────────────────────────────────────────────

    @Test
    fun `a prepended shelf leads the page and claims its title`() {
        val seen = mutableSetOf<String>()
        val merged = mergeShelves(
            existing = listOf(shelf("New releases")),
            incoming = listOf(shelf("Recents")),
            seenTitles = seen,
            replace = false,
            prepend = true,
        )
        assertEquals(listOf("Recents", "New releases"), merged!!.map { it.title })
    }

    @Test
    fun `a shelf whose title is already claimed does not ride in twice`() {
        val seen = mutableSetOf<String>()
        mergeShelves(emptyList(), listOf(shelf("Recents")), seen, replace = true, prepend = true)
        // The core feed arrives with YouTube's own same-titled shelf: the
        // account's shelf is already on the page, and the incoming duplicate
        // is what the dedupe drops — the replacement falls out of the rule
        // without a special case.
        val merged = mergeShelves(
            existing = listOf(shelf("Recents")),
            incoming = listOf(shelf("New releases"), shelf("recents")),
            seenTitles = seen,
            replace = false,
            prepend = false,
        )
        assertEquals(listOf("Recents", "New releases"), merged!!.map { it.title })
    }

    @Test
    fun `a trailing append with no newcomers changes nothing`() {
        val seen = mutableSetOf("recents")
        val merged = mergeShelves(
            existing = listOf(shelf("Recents")),
            incoming = listOf(shelf("Recents")),
            seenTitles = seen,
            replace = false,
            prepend = false,
        )
        assertNull(merged)
    }

    // ── Search history ───────────────────────────────────────────────────────

    private fun entity(id: String) =
        SearchHistoryEntity(id = id, title = id, subtitle = "", artworkUrl = null, entityType = EntityType.TRACK)

    @Test
    fun `a record moves its match to the front and caps the list`() {
        val list = (1..SearchHistory.MAX_ENTRIES + 5).map { entity("e$it") }
        val merged = merged(entity("e30"), list)
        assertEquals("e30", merged.first().id)
        assertEquals(SearchHistory.MAX_ENTRIES, merged.size)
        // Case-insensitive by id, upstream's rule: a re-tap under a different
        // case is the same entity moved, not a second row.
        val caseMoved = merged(entity("E2"), listOf(entity("e1"), entity("e2")))
        assertEquals(listOf("E2", "e1"), caseMoved.map { it.id })
    }

    // ── Update versions ──────────────────────────────────────────────────────

    @Test
    fun `numeric dotted comparison`() {
        assertTrue(com.music.bitchord.data.AppUpdateChecker.isNewer("1.10", "1.9"))
        assertTrue(!com.music.bitchord.data.AppUpdateChecker.isNewer("1.9", "1.10"))
        assertTrue(!com.music.bitchord.data.AppUpdateChecker.isNewer("1.0", "1.0"))
    }

    @Test
    fun `a suffix build is older than the plain release of the same numbers`() {
        // The desktop's own version shape: 0.1.0-desktop vs a v0.1.0 tag.
        assertTrue(com.music.bitchord.data.AppUpdateChecker.isNewer("0.1.0", "0.1.0-desktop"))
        // And a beta is older than its own release, upstream's tester rule.
        assertTrue(com.music.bitchord.data.AppUpdateChecker.isNewer("1.2", "1.2-beta2"))
        assertTrue(!com.music.bitchord.data.AppUpdateChecker.isNewer("1.2", "1.2"))
    }
}
