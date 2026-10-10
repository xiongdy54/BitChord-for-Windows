package com.music.bitchord.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The folder list's encoding — a JSON array in one property value, chosen
 * because a path may legitimately carry a comma and a split-on-comma encoding
 * would silently corrupt it. These pin the round-trip and the two graceful
 * degradations: nothing stored, and something unparseable.
 */
class LocalMediaSettingsTest {

    @Test
    fun `paths round-trip, including commas and backslashes`() {
        val folders = listOf(
            "C:\\Music, misc\\Lossless",
            "/home/user/Music",
            "D:\\band's \"greatest\" albums",
            "E:\\unicode 中文",
        )
        assertEquals(folders, decodeFolders(encodeFolders(folders)))
    }

    @Test
    fun `an empty list encodes to a blank and reads back empty`() {
        assertEquals("", encodeFolders(emptyList()))
        assertEquals(emptyList(), decodeFolders(encodeFolders(emptyList())))
        assertEquals(emptyList(), decodeFolders(null))
        assertEquals(emptyList(), decodeFolders(""))
    }

    @Test
    fun `garbage reads as none chosen rather than throwing`() {
        assertTrue(decodeFolders("C:\\Music,D:\\More").isEmpty())
        assertTrue(decodeFolders("[broken").isEmpty())
    }

    @Test
    fun `the sort and view type enums are upstream's four and two`() {
        assertEquals(
            listOf("TITLE_ASC", "TITLE_DESC", "DATE_ADDED", "DATE_MODIFIED"),
            LocalMusicSort.entries.map { it.name },
        )
        assertEquals(listOf("LIST", "GRID"), LibraryViewType.entries.map { it.name })
    }
}
