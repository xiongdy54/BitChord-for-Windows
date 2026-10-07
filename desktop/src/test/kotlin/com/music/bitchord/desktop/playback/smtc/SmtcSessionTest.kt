package com.music.bitchord.desktop.playback.smtc

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the system surface is told, and only when it changed — the semantic
 * half of SMTC, against a backend that remembers calls the way the WinRT one
 * makes them.
 */
class SmtcSessionTest {

    private class FakeBackend : SmtcBackend {
        override var isAttached = false
        override var onButton: ((SmtcButton) -> Unit)? = null
        override var onSeekRequested: ((Long) -> Unit)? = null
        val enabledFlags = mutableListOf<Boolean>()
        val statuses = mutableListOf<Int>()
        val metadata = mutableListOf<Triple<String?, String?, String?>>()
        val timelines = mutableListOf<Pair<Long, Long>>()
        var releaseCount = 0

        override fun attach(hwndHandle: Long) {
            isAttached = true
        }

        override fun setPlaybackStatus(status: Int) {
            statuses += status
        }

        override fun setMetadata(title: String?, artist: String?, artworkUrl: String?) {
            metadata += Triple(title, artist, artworkUrl)
        }

        override fun setTimeline(positionMs: Long, durationMs: Long) {
            timelines += positionMs to durationMs
        }

        override fun release() {
            releaseCount++
            isAttached = false
        }
    }

    private fun song(id: String, title: String = "T$id") = Song(id, title, "A$id", "art/$id")

    @Test
    fun `the first update pushes status, metadata and timeline once`() {
        val backend = FakeBackend()
        val session = SmtcSession(backend)
        session.attach(1)
        session.update(song("a"), isPlaying = true, positionMs = 1000, durationMs = 269_000)
        assertEquals(listOf(SmtcSession.STATUS_PLAYING), backend.statuses)
        assertEquals(1, backend.metadata.size)
        assertEquals(Triple("Ta", "Aa", "art/a"), backend.metadata[0])
        assertEquals(1, backend.timelines.size)
        assertEquals(1000L to 269_000L, backend.timelines[0])
    }

    @Test
    fun `an unchanged picture pushes nothing`() {
        val backend = FakeBackend()
        val session = SmtcSession(backend)
        session.attach(1)
        session.update(song("a"), isPlaying = true, positionMs = 1000, durationMs = 269_000)
        val afterFirst = backend.metadata.size
        session.update(song("a"), isPlaying = true, positionMs = 1000, durationMs = 269_000)
        assertEquals(afterFirst, backend.metadata.size)
        assertEquals(1, backend.statuses.size)
        assertEquals(1, backend.timelines.size)
    }

    @Test
    fun `a track change re-pushes the metadata and the timeline`() {
        val backend = FakeBackend()
        val session = SmtcSession(backend)
        session.attach(1)
        session.update(song("a"), isPlaying = true, positionMs = 1000, durationMs = 269_000)
        session.update(song("b"), isPlaying = true, positionMs = 0, durationMs = 180_000)
        assertEquals(2, backend.metadata.size)
        assertEquals(Triple("Tb", "Ab", "art/b"), backend.metadata[1])
        assertEquals(2, backend.timelines.size)
    }

    @Test
    fun `a pause pushes only the status`() {
        val backend = FakeBackend()
        val session = SmtcSession(backend)
        session.attach(1)
        session.update(song("a"), isPlaying = true, positionMs = 1000, durationMs = 269_000)
        val metadataBefore = backend.metadata.size
        val timelinesBefore = backend.timelines.size
        session.update(song("a"), isPlaying = false, positionMs = 1000, durationMs = 269_000)
        assertEquals(listOf(SmtcSession.STATUS_PLAYING, SmtcSession.STATUS_PAUSED), backend.statuses)
        assertEquals(metadataBefore, backend.metadata.size)
        assertEquals(timelinesBefore, backend.timelines.size)
    }

    @Test
    fun `a position tick re-anchors the timeline alone`() {
        val backend = FakeBackend()
        val session = SmtcSession(backend)
        session.attach(1)
        session.update(song("a"), isPlaying = true, positionMs = 1000, durationMs = 269_000)
        val metadataBefore = backend.metadata.size
        val statusesBefore = backend.statuses.size
        session.update(song("a"), isPlaying = true, positionMs = 3000, durationMs = 269_000)
        assertEquals(statusesBefore, backend.statuses.size)
        assertEquals(metadataBefore, backend.metadata.size)
        assertEquals(3000L to 269_000L, backend.timelines.last())
    }

    @Test
    fun `silence with no track is Closed, with the metadata cleared`() {
        val backend = FakeBackend()
        val session = SmtcSession(backend)
        session.attach(1)
        session.update(song("a"), isPlaying = true, positionMs = 1000, durationMs = 269_000)
        session.update(null, isPlaying = false, positionMs = 0, durationMs = 0)
        assertEquals(SmtcSession.STATUS_CLOSED, backend.statuses.last())
        val last = backend.metadata.last()
        assertNull(last.first)
        assertNull(last.second)
        assertNull(last.third)
    }

    @Test
    fun `updates before attach are dropped, and the same update lands once attached`() {
        val backend = FakeBackend()
        val session = SmtcSession(backend)
        session.update(song("a"), isPlaying = true, positionMs = 0, durationMs = 100)
        assertTrue(backend.statuses.isEmpty())
        session.attach(1)
        session.update(song("a"), isPlaying = true, positionMs = 0, durationMs = 100)
        assertEquals(1, backend.statuses.size)
    }

    @Test
    fun `buttons and seek requests reach the session's callbacks`() {
        val backend = FakeBackend()
        val session = SmtcSession(backend)
        session.attach(1)
        val pressed = mutableListOf<SmtcButton>()
        val seeks = mutableListOf<Long>()
        session.onButton = { pressed += it }
        session.onSeekRequested = { seeks += it }
        backend.onButton?.invoke(SmtcButton.NEXT)
        backend.onSeekRequested?.invoke(42_000)
        assertEquals(listOf(SmtcButton.NEXT), pressed)
        assertEquals(listOf(42_000L), seeks)
    }

    @Test
    fun `release hands the slot up exactly once`() {
        val backend = FakeBackend()
        val session = SmtcSession(backend)
        session.attach(1)
        session.release()
        assertEquals(1, backend.releaseCount)
        session.release()
        assertEquals(1, backend.releaseCount)
    }
}
