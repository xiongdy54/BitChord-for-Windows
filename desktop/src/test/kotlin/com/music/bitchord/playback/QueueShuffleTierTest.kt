// Ported from app/src/test/java/com/music/bitchord/QueueShuffleTierTest.kt. Two substitutions,
// both forced by Media3 going away: the queue is built as Songs rather than through
// `toMediaItem()` (so the tier is read off `Song.queueTier`, not a MediaItem's metadata), and
// the fake player stops being a `java.lang.reflect.Proxy` over Media3's forty-member `Player` —
// desktop has a real fake for that, FakeQueueHost. Every ordering expectation is the original's.
package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QueueShuffleTierTest {

    private fun testSong(
        id: String,
        tier: QueueTier = QueueTier.CONTEXT,
        entryId: String = "entry-$id",
    ) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist $id",
        thumbnailUrl = null,
        queueTier = tier,
        queueEntryId = entryId,
    )

    /** The queue as the shuffle sees it: each row's entry id, in list order. */
    private fun FakeQueueHost.entryIds(): List<String> = items.map { it.queueEntryId ?: it.videoId }

    /** `QueueShuffle` is a process-wide singleton, so every case starts from a known flag. */
    @BeforeTest
    fun shuffleOff() {
        QueueShuffle.setEnabled(false)
    }

    /**
     * [QueueShuffle.toggle] persists through [AppSettings.setShuffleEnabled], which writes the
     * real `settings.properties`. Put the default back — in an `@AfterTest` rather than the last
     * two lines of the case, so a mid-loop assertion failure still cannot leave the user's next
     * session starting shuffled. Nothing here reads the flag back afterwards.
     */
    @AfterTest
    fun leaveTheFlagAndTheFileAtTheirDefaults() {
        AppSettings.setShuffleEnabled(false)
        QueueShuffle.setEnabled(false)
    }

    @Test
    fun `startingOrder preserves USER_QUEUE items at front and shuffles context`() {
        val selected = testSong("selected", tier = QueueTier.CONTEXT)
        val u1 = testSong("u1", tier = QueueTier.USER_QUEUE)
        val u2 = testSong("u2", tier = QueueTier.USER_QUEUE)
        val c1 = testSong("c1", tier = QueueTier.CONTEXT)
        val c2 = testSong("c2", tier = QueueTier.CONTEXT)
        val c3 = testSong("c3", tier = QueueTier.CONTEXT)
        val a1 = testSong("a1", tier = QueueTier.AUTOPLAY)

        val input = listOf(selected, u1, u2, c1, c2, c3, a1)
        val result = QueueShuffle.startingOrder(input, startIndex = 0)

        assertEquals("selected", result[0].videoId)
        assertEquals("u1", result[1].videoId)
        assertEquals("u2", result[2].videoId)

        // Context items are between indices 3 and 5 inclusive
        val resultContextIds = result.subList(3, 6).map { it.videoId }.toSet()
        assertEquals(setOf("c1", "c2", "c3"), resultContextIds)

        // Autoplay at index 6
        assertEquals("a1", result[6].videoId)
    }

    @Test
    fun `restoreOrder deterministically restores duplicate songs using unique queueEntryIds`() {
        val entry1 = "uuid-1"
        val entry2 = "uuid-2"
        val original = listOf(entry1, entry2)
        val shuffled = listOf(entry2, entry1)

        val restoredIndices = QueueShuffle.restoreOrder(shuffled, original)
        val restoredEntries = restoredIndices.map { shuffled[it] }

        assertEquals(original, restoredEntries)
    }

    @Test
    fun `reproduce album alternating skip and shuffle toggle`() {
        val albumSongs = (1..10).map { testSong("track-$it", QueueTier.CONTEXT, "entry-$it") }
        val host = FakeQueueHost(albumSongs, currentIndex = 0)

        repeat(20) { step ->
            // The original's player.seekToNextMediaItem(), bounded by the tail the same way.
            if (host.currentIndex < host.itemCount - 1) host.jumpTo(host.currentIndex + 1)
            // When reaching near the end of the album, simulate AutoPlay appending tracks
            // with stable unique IDs — an edit entirely ahead of the needle.
            if (host.currentIndex >= 8 && host.itemCount == 10) {
                host.replaceRange(
                    host.itemCount,
                    host.itemCount,
                    (1..5).map { testSong("autoplay-$it", QueueTier.AUTOPLAY, "entry-auto-$it") },
                )
            }
            QueueShuffle.toggle(host)

            // Invariant:
            // 1. All queueEntryIds remain distinct across the queue
            val entryIds = host.entryIds()
            assertEquals(
                host.items.size,
                entryIds.toSet().size,
                "All queue entry IDs must remain unique at step $step",
            )
        }
    }

    /**
     * What the desktop rewrite is actually for: the permutation lands on the list itself.
     *
     * On Android this is where [QueueShuffle] handed a custom command across the session
     * boundary and let the service do the editing, because the controller's copy of the queue
     * had no URIs left on it. That branch is gone, so nothing else here proves the edit reaches
     * the queue — and that it reaches nothing but the queue.
     */
    @Test
    fun `shuffling rewrites the rows ahead of the needle and nothing else`() {
        val host = FakeQueueHost(
            listOf(
                testSong("now"),
                testSong("u1", QueueTier.USER_QUEUE),
                testSong("c1"),
                testSong("c2"),
                testSong("c3"),
                testSong("a1", QueueTier.AUTOPLAY),
            ),
            currentIndex = 0,
        )
        val queued = host.entryIds()

        QueueShuffle.toggle(host)

        assertEquals(6, host.itemCount)
        assertEquals(0, host.currentIndex)
        // The row under the needle is not part of the span that gets rewritten.
        assertEquals("entry-now", host.songAt(0)?.queueEntryId)
        assertEquals(queued.sorted(), host.entryIds().sorted())
        // shuffle's order is userQueueIndices + shuffledSection(context) + shuffledSection(autoplay),
        // applied at from = currentIndex + 1, so the user row lands pinned at slot 1 and the
        // three context rows permute among themselves in the slots after it.
        assertEquals(QueueTier.USER_QUEUE, host.tierAt(1))
        assertEquals("entry-u1", host.songAt(1)?.queueEntryId)
        assertEquals(
            listOf("entry-c1", "entry-c2", "entry-c3"),
            host.entryIds().subList(2, 5).sorted(),
        )
        assertEquals(QueueTier.AUTOPLAY, host.tierAt(5))
        // Applying an order is an edit to the list, not a seek: the needle stays put and the
        // row under it is never restarted.
        assertTrue(host.playbackCalls.isEmpty())
        assertTrue(QueueShuffle.enabled.value)
    }

    @Test
    fun `toggling twice puts the rows back in the order they were queued`() {
        val host = FakeQueueHost(
            listOf(testSong("now"), testSong("c1"), testSong("c2"), testSong("c3"),
                testSong("a1", QueueTier.AUTOPLAY)),
            currentIndex = 0,
        )
        val queued = host.entryIds()

        QueueShuffle.toggle(host)
        // Not vacuous: three shufflable rows means avoidIdentityShuffle guarantees a move.
        assertNotEquals(queued, host.entryIds())
        QueueShuffle.toggle(host)

        assertEquals(queued, host.entryIds())
        assertEquals(0, host.currentIndex)
        assertFalse(QueueShuffle.enabled.value)
        assertTrue(host.playbackCalls.isEmpty())
    }

    /** The early return when there is nothing left to shuffle, kept verbatim through the port. */
    @Test
    fun `a needle on the last row flips the flag without rewriting anything`() {
        val host = FakeQueueHost(listOf(testSong("now"), testSong("c1")), currentIndex = 1)
        val queued = host.entryIds()

        QueueShuffle.toggle(host)
        assertEquals(queued, host.entryIds())
        assertEquals(1, host.currentIndex)
        assertTrue(QueueShuffle.enabled.value)

        QueueShuffle.toggle(host)
        assertEquals(queued, host.entryIds())
        assertFalse(QueueShuffle.enabled.value)
    }
}
