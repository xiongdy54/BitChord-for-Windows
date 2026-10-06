package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LogRingTest {

    @Test
    fun `keeps everything while under capacity, oldest first`() {
        val ring = LogRing(capacity = 4)
        ring.record("a")
        ring.record("b")
        assertEquals("a\nb", ring.dump())
    }

    @Test
    fun `overflow drops the oldest lines, not the newest`() {
        val ring = LogRing(capacity = 3)
        ('a'..'f').forEach { ring.record(it.toString()) }
        assertEquals("d\ne\nf", ring.dump())
    }

    @Test
    fun `a multi-line record occupies as many rows as it has`() {
        // A stack trace printed through w/e spans several lines; the dump
        // hands them back verbatim, so what lands in the clipboard is what
        // was printed.
        val ring = LogRing(capacity = 3)
        ring.record("W/tag: boom\nat frame one\nat frame two")
        assertEquals("W/tag: boom\nat frame one\nat frame two", ring.dump())
    }

    @Test
    fun `a full ring holding exactly capacity is not disturbed by reads`() {
        val ring = LogRing(capacity = 2)
        ring.record("a")
        ring.record("b")
        assertEquals("a\nb", ring.dump())
        assertEquals("a\nb", ring.dump())
        ring.record("c")
        assertEquals("b\nc", ring.dump())
    }

    @Test
    fun `capacity zero is refused — a ring that keeps nothing is not a ring`() {
        assertFailsWith<IllegalArgumentException> { LogRing(capacity = 0) }
    }

    @Test
    fun `the default ring is the size the copy-log action documents`() {
        assertTrue(LogRing.DEFAULT_CAPACITY >= 200)
    }
}
