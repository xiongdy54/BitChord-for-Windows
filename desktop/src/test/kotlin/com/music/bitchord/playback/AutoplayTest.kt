package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The re-arm condition and the batch loader, as a truth table —
 * `AutoplayFallbackTest` ported and grown.
 */
class AutoplayTest {

    private fun song(id: String) = Song(id, "T$id", "A$id", null)

    @Test
    fun `enabled autoplay refreshes when current track has no queue after it`() {
        assertTrue(
            autoplayQueueNeedsRefresh(
                enabled = true,
                repeatAll = false,
                currentIndex = 2,
                itemCount = 3,
                loadInProgress = false,
            ),
        )
    }

    @Test
    fun `an existing queue or active load does not start a duplicate refresh`() {
        assertFalse(autoplayQueueNeedsRefresh(true, false, 1, 3, false))
        assertFalse(autoplayQueueNeedsRefresh(true, false, 2, 3, true))
    }

    @Test
    fun `disabled autoplay repeat all and a missing current track do not refresh`() {
        assertFalse(autoplayQueueNeedsRefresh(false, false, 0, 1, false))
        assertFalse(autoplayQueueNeedsRefresh(true, true, 0, 1, false))
        assertFalse(autoplayQueueNeedsRefresh(true, false, -1, 0, false))
    }

    @Test
    fun `a loaded batch is stamped into the AUTOPLAY tier with the seed's own source`() = kotlinx.coroutines.runBlocking {
        val seed = song("seed").copy(
            radioName = "Radio",
            playbackSource = "Playlist",
            playbackSourceType = PlaybackSourceType.BROWSE,
            playbackSourceId = "PL",
        )
        val related = listOf(song("r1"), song("r2"))
        val loaded = loadAutoplayTracks(
            existing = emptyList(),
            seedSong = seed,
            radio = { Result.success(related) },
        ).getOrThrow()
        assertEquals(listOf("r1", "r2"), loaded.map { it.videoId })
        loaded.forEach {
            assertEquals(QueueTier.AUTOPLAY, it.queueTier)
            assertEquals("Radio", it.radioName)
            assertEquals("Playlist", it.playbackSource)
            assertEquals("PL", it.playbackSourceId)
        }
    }

    @Test
    fun `the loader comes back empty when the radio answer is empty`() = kotlinx.coroutines.runBlocking {
        val loaded = loadAutoplayTracks(
            emptyList(),
            song("seed"),
            radio = { Result.success(emptyList()) },
        ).getOrThrow()
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `extend is what keeps the batch honest about what the queue already holds`() = kotlinx.coroutines.runBlocking {
        val loaded = loadAutoplayTracks(
            existing = listOf(song("in-queue")),
            seedSong = song("seed"),
            radio = { Result.success(listOf(song("in-queue"), song("fresh"))) },
        ).getOrThrow()
        assertEquals(listOf("fresh"), loaded.map { it.videoId })
    }

    @Test
    fun `a failed radio answer is the failure, not a silent empty`() = kotlinx.coroutines.runBlocking {
        val result = loadAutoplayTracks(
            emptyList(),
            song("seed"),
            radio = { Result.failure(IllegalStateException("down")) },
        )
        assertTrue(result.isFailure)
    }
}
