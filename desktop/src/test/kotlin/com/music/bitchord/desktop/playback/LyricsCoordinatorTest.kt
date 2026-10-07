package com.music.bitchord.desktop.playback

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lyric lookup's seams, wired to a fetch that answers from a script —
 * the network and the sixteen sources behind it are
 * [com.music.bitchord.data.lyrics.LyricsRepository]'s business. What this file
 * pins: the winner lands and the lookup is marked done, the dedup and duration
 * gates turn away what they exist to turn away, a track switch retires the old
 * lookup, and the provider picker applies from memory, defers while fetching,
 * and goes online only for an untouched source.
 */
class LyricsCoordinatorTest {

    private class ScriptedCall(
        val videoId: String,
        val sources: Set<LyricsSource>,
        val order: List<LyricsSource>,
    )

    /** A fetch whose answers (and silences) come from the case. */
    private class ScriptedFetch : Fetcher {
        val calls = mutableListOf<ScriptedCall>()

        /** What the next call does. Defaults to a silent miss; may suspend to hold a call open. */
        var respond: suspend ScriptedCall.(Sink) -> LyricsRepository.Result? = { null }

        class Sink(
            val started: (LyricsSource) -> Unit,
            val finished: (LyricsSource, LyricsRepository.Result?) -> Unit,
            val cancelled: (LyricsSource) -> Unit,
        )

        override suspend fun lyrics(
            videoId: String,
            title: String,
            artist: String,
            durationMs: Long,
            album: String?,
            sources: Set<LyricsSource>,
            order: List<LyricsSource>,
            prioritizeSyllableSync: Boolean,
            onSourceStarted: ((LyricsSource) -> Unit)?,
            onSourceResult: ((LyricsSource, LyricsRepository.Result?) -> Unit)?,
            onSourceCancelled: ((LyricsSource) -> Unit)?,
        ): LyricsRepository.Result? {
            val call = ScriptedCall(videoId, sources, order)
            calls += call
            val sink = Sink(
                started = { onSourceStarted?.invoke(it) },
                finished = { source, result -> onSourceResult?.invoke(source, result) },
                cancelled = { onSourceCancelled?.invoke(it) },
            )
            return respond(call, sink)
        }
    }

    private fun lines(vararg texts: String) = texts.map { LyricLine(timeMs = 0L, text = it) }

    private fun result(source: LyricsSource, vararg texts: String) =
        LyricsRepository.Result(source, lines(*texts))

    /** Unconfined, like PlayerControllerTest: every hop runs inline, no concurrency to await. */
    private fun coordinator(fetch: ScriptedFetch) =
        LyricsCoordinator(CoroutineScope(Dispatchers.Unconfined), fetch)

    @Test
    fun `the winner lands, is credited, and the lookup is marked done`() {
        val fetch = ScriptedFetch()
        val answer = result(LyricsSource.LRCLIB, "one", "two")
        fetch.respond = { sink ->
            sink.started(LyricsSource.LRCLIB)
            sink.finished(LyricsSource.LRCLIB, answer)
            answer
        }
        val lyrics = coordinator(fetch)

        lyrics.loadLyrics("vid", "Title", "Artist", 1000)

        assertEquals(answer.lines, lyrics.lyrics.value)
        assertEquals(LyricsSource.LRCLIB, lyrics.lyricsSource.value)
        assertTrue(lyrics.lyricsChecked.value)
        assertEquals(
            LyricsProviderState.FOUND,
            lyrics.lyricsProviderStates.value[LyricsSource.LRCLIB],
        )
    }

    @Test
    fun `a repeat call for the same track and sources is dropped`() {
        val fetch = ScriptedFetch()
        val lyrics = coordinator(fetch)
        lyrics.loadLyrics("vid", "Title", "Artist", 1000)
        lyrics.loadLyrics("vid", "Title", "Artist", 1000)

        assertEquals(1, fetch.calls.size)
    }

    @Test
    fun `a call before the duration lands is dropped and the re-trigger carries it`() {
        val fetch = ScriptedFetch()
        val lyrics = coordinator(fetch)

        lyrics.loadLyrics("vid", "Title", "Artist", 0)
        assertEquals(0, fetch.calls.size)
        assertFalse(lyrics.lyricsChecked.value)

        lyrics.loadLyrics("vid", "Title", "Artist", 1000)
        assertEquals(1, fetch.calls.size)
    }

    @Test
    fun `switched off means checked and empty, with no call`() {
        val fetch = ScriptedFetch()
        val lyrics = coordinator(fetch)
        com.music.bitchord.data.settings.AppSettings.setSyncedLyrics(false)
        try {
            lyrics.loadLyrics("vid", "Title", "Artist", 1000)

            assertTrue(lyrics.lyricsChecked.value)
            assertNull(lyrics.lyrics.value)
            assertEquals(0, fetch.calls.size)
        } finally {
            com.music.bitchord.data.settings.AppSettings.setSyncedLyrics(true)
        }
    }

    @Test
    fun `a track switch retires the old lookup — its answer never lands`() {
        val fetch = ScriptedFetch()
        val gates = listOf(CompletableDeferred<LyricsRepository.Result?>(), CompletableDeferred<LyricsRepository.Result?>())
        var nth = 0
        fetch.respond = { sink ->
            val gate = gates[nth++]
            sink.started(LyricsSource.LRCLIB)
            gate.await()
        }
        val lyrics = coordinator(fetch)
        lyrics.loadLyrics("first", "Title", "Artist", 1000)
        assertEquals(
            LyricsProviderState.FETCHING,
            lyrics.lyricsProviderStates.value[LyricsSource.LRCLIB],
        )

        lyrics.loadLyrics("second", "Title", "Artist", 1000)
        // The switch reset the picker; the first track's answer, arriving now,
        // belongs to a lookup nobody is waiting on — the cancelled job dies
        // before its answer can land, and only the second gate speaks.
        gates[0].complete(result(LyricsSource.LRCLIB, "stale"))
        val fresh = result(LyricsSource.LRCLIB, "fresh")
        gates[1].complete(fresh)

        assertEquals(fresh.lines, lyrics.lyrics.value)
        assertTrue(lyrics.lyricsChecked.value)
    }

    @Test
    fun `a completed hit is applied from memory`() {
        val fetch = ScriptedFetch()
        val automatic = result(LyricsSource.LRCLIB, "lrc")
        val manual = result(LyricsSource.GENIUS, "genius")
        fetch.respond = { sink ->
            // The automatic call races every enabled source; the manual one
            // carries exactly the source that was picked.
            if (sources.size == 1 && LyricsSource.GENIUS in sources) {
                sink.finished(LyricsSource.GENIUS, manual)
                manual
            } else {
                sink.finished(LyricsSource.LRCLIB, automatic)
                automatic
            }
        }
        val lyrics = coordinator(fetch)
        lyrics.loadLyrics("vid", "Title", "Artist", 1000)

        lyrics.selectLyricsProvider(LyricsSource.GENIUS)

        assertEquals(2, fetch.calls.size)
        assertEquals(setOf(LyricsSource.GENIUS), fetch.calls[1].sources)
        assertEquals(manual.lines, lyrics.lyrics.value)
        assertEquals(LyricsSource.GENIUS, lyrics.lyricsSource.value)
    }

    @Test
    fun `selecting a source that is still fetching defers to the running attempt`() {
        val fetch = ScriptedFetch()
        val gate = CompletableDeferred<LyricsRepository.Result?>()
        val answer = result(LyricsSource.LRCLIB, "lrc")
        fetch.respond = { sink ->
            sink.started(LyricsSource.LRCLIB)
            gate.await() ?: answer
        }
        val lyrics = coordinator(fetch)
        lyrics.loadLyrics("vid", "Title", "Artist", 1000)

        lyrics.selectLyricsProvider(LyricsSource.LRCLIB)
        gate.complete(answer)

        assertEquals(1, fetch.calls.size)
        assertEquals(answer.lines, lyrics.lyrics.value)
        assertEquals(LyricsSource.LRCLIB, lyrics.lyricsSource.value)
    }

    @Test
    fun `a miss marks the source not found and selecting it again is inert`() {
        val fetch = ScriptedFetch()
        fetch.respond = { sink ->
            sink.finished(LyricsSource.LRCLIB, null)
            null
        }
        val lyrics = coordinator(fetch)
        lyrics.loadLyrics("vid", "Title", "Artist", 1000)
        assertEquals(
            LyricsProviderState.NOT_FOUND,
            lyrics.lyricsProviderStates.value[LyricsSource.LRCLIB],
        )

        lyrics.selectLyricsProvider(LyricsSource.LRCLIB)

        assertEquals(1, fetch.calls.size)
    }
}
