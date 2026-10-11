package com.music.bitchord

import com.music.bitchord.download.DownloadProgress
import com.music.bitchord.download.DownloadSession
import com.music.bitchord.download.DownloadState
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.resumeOffsetIfSameRendition
import com.music.bitchord.ui.screens.ReleaseDownloadState
import com.music.bitchord.ui.screens.releaseDownloadState
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Slice 20's arithmetic: the resume guard that keeps two renditions from
 * splicing into one file, the release-download state the header pill words,
 * the per-release `requested` mark, and the paused row's place in the
 * manager's counts.
 */
class DownloadHoldTest {

    // ── The resume guard ─────────────────────────────────────────────────────

    @Test
    fun `a matching rendition resumes from the part's length`() {
        assertEquals(
            123_456L,
            resumeOffsetIfSameRendition(
                partExtension = "m4a", partQuality = "HIGH", partDescribe = "256kbps audio/mp4",
                partLength = 123_456L,
                routeExtension = "m4a", routeQuality = "HIGH", routeDescribe = "256kbps audio/mp4",
            ),
        )
    }

    @Test
    fun `any drift between the renditions restarts the bytes`() {
        val base = {
            // The matching row, one field off at a time.
            listOf(
                { resumeOffsetIfSameRendition("m4a", "HIGH", "256kbps audio/mp4", 500L, "webm", "HIGH", "256kbps audio/mp4") },
                { resumeOffsetIfSameRendition("m4a", "STANDARD", "256kbps audio/mp4", 500L, "m4a", "HIGH", "256kbps audio/mp4") },
                // The splice the third field exists to stop: same extension and
                // rung, a source answering where YouTube did before.
                { resumeOffsetIfSameRendition("m4a", "HIGH", "Lossless from source", 500L, "m4a", "HIGH", "256kbps audio/mp4") },
                // An empty part is not a resume either.
                { resumeOffsetIfSameRendition("m4a", "HIGH", "256kbps audio/mp4", 0L, "m4a", "HIGH", "256kbps audio/mp4") },
            )
        }()
        base.forEach { action -> assertEquals(0L, action(), "drift must restart, not splice") }
    }

    // ── The release pill's state ─────────────────────────────────────────────

    @Test
    fun `the release state reads all-saved, then moving, then the invitation`() {
        val ids = listOf("a", "b")
        assertEquals(
            ReleaseDownloadState.ALL_SAVED,
            releaseDownloadState(ids, active = emptyMap(), saved = mapOf("a" to "p", "b" to "p")),
        )
        assertEquals(
            ReleaseDownloadState.MOVING,
            releaseDownloadState(ids, active = mapOf("b" to DownloadState.Running(0.5f)), saved = mapOf("a" to "p")),
        )
        assertEquals(
            ReleaseDownloadState.IDLE,
            releaseDownloadState(ids, active = emptyMap(), saved = emptyMap()),
        )
    }

    // ── The per-release mark ─────────────────────────────────────────────────

    @Test
    fun `a release's ask accumulates without clobbering another's`() {
        Downloads.markRequested("album-1", listOf("a", "b"))
        Downloads.markRequested("album-2", listOf("b", "c"))
        Downloads.markRequested("album-1", listOf("b", "d"))
        val requested = Downloads.requested.value
        assertEquals(setOf("a", "b", "d"), requested["album-1"])
        assertEquals(setOf("b", "c"), requested["album-2"])
    }

    // ── Paused rows in the manager ───────────────────────────────────────────

    @Test
    fun `a paused row is neither waiting nor settled, and counts its partial progress`() {
        val session = DownloadSession
        session.clear()
        try {
            val song = Song("x", "T", "A", null)
            session.queued(song, "album-1")
            session.running("x", 0.4f)
            session.paused("x", 0.4f)
            val state = session.state.value
            assertEquals(0, state.waiting, "paused is not the queue working")
            assertEquals(1, state.paused)
            assertEquals(false, state.busy, "a held batch is not a running one")
            assertEquals(0.4f, state.fraction)
            val row = state.items.single()
            assertEquals(false, row.progress.settled, "a hold is not an outcome")
        } finally {
            session.clear()
        }
    }
}
