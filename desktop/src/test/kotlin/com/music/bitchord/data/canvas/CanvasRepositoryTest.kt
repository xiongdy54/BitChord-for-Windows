package com.music.bitchord.data.canvas

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The repository's own judgements, driven through the internal [ChainLink]
 * seam with fakes — the cache (misses included), the serialisation, the
 * priority flip and the noise stripping. The production chain built by
 * [CanvasRepository.songChain] is pinned by name in its own test.
 */
class CanvasRepositoryTest {

    /** A fake provider: records the searches it was handed, answers with [answer]. */
    private class FakeChain(
        val answer: (title: String, artist: String, album: String?) -> CanvasArtwork? = { _, _, _ -> null },
    ) {
        val searches = mutableListOf<Triple<String, String, String?>>()
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        fun links(vararg names: String): List<ChainLink> = names.map { name ->
            ChainLink(name) { title, artist, album ->
                searches += Triple(name, title, album)
                gate?.await()
                answer(title, artist, album)
            }
        }
    }

    private fun song(
        videoId: String,
        title: String = "Crazy In Love",
        artist: String = "Beyoncé",
        albumName: String? = null,
        localUri: String? = null,
    ) = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        albumName = albumName,
        localUri = localUri,
    )

    private val artwork = CanvasArtwork(url = "https://cdn.test/clip.mp4", title = "Crazy In Love", artist = "Beyoncé")

    // ---- the cache -------------------------------------------------------

    @Test
    fun `a hit is answered from the cache the second time`() = runTest {
        val fake = FakeChain { _, _, _ -> artwork }
        val chain = fake.links("A", "B")

        CanvasRepository.canvasFor(song("hit"), spotifyFirst = false, chain = chain)
        CanvasRepository.canvasFor(song("hit"), spotifyFirst = false, chain = chain)

        // One pass through the chain the first time: A answered, so B was
        // never paid for. The second lookup touched no provider at all.
        assertEquals(listOf("A"), fake.searches.map { it.first })
    }

    @Test
    fun `a miss is cached too — negative entries included`() = runTest {
        val fake = FakeChain { _, _, _ -> null }
        val chain = fake.links("A", "B", "C", "D")

        CanvasRepository.canvasFor(song("miss"), spotifyFirst = false, chain = chain)
        CanvasRepository.canvasFor(song("miss"), spotifyFirst = false, chain = chain)

        // The first pass walked the whole chain and learned "nothing here";
        // the second learned nothing new, and paid nothing.
        assertEquals(listOf("A", "B", "C", "D"), fake.searches.map { it.first })
    }

    @Test
    fun `a miss reached without the album is retried once it arrives`() = runTest {
        val fake = FakeChain { title, artist, album ->
            if (album == null) null else CanvasArtwork(url = "https://cdn.test/clip.mp4", title = title, artist = artist)
        }
        val chain = fake.links("A")

        CanvasRepository.canvasFor(song("blind-miss", albumName = null), spotifyFirst = false, chain = chain)
        val landed = CanvasRepository.canvasFor(
            song("blind-miss", albumName = "B'Day"),
            spotifyFirst = false,
            chain = chain,
        )

        // The blind miss is provisional; the album name turns it into a real
        // question and the chain is asked again — and this time it answers.
        assertEquals(2, fake.searches.size)
        assertNotNull(landed)
    }

    @Test
    fun `a hit reached without the album is not retried when it arrives`() = runTest {
        val fake = FakeChain { _, _, _ -> artwork }
        val chain = fake.links("A")

        CanvasRepository.canvasFor(song("confirmed-hit", albumName = null), spotifyFirst = false, chain = chain)
        CanvasRepository.canvasFor(song("confirmed-hit", albumName = "B'Day"), spotifyFirst = false, chain = chain)

        // A hit is a hit — the album could only have confirmed it.
        assertEquals(1, fake.searches.size)
    }

    // ---- identity guards -------------------------------------------------

    @Test
    fun `a local file with no catalogue identity is a miss without a request`() = runTest {
        val fake = FakeChain { _, _, _ -> artwork }
        val chain = fake.links("A")

        val found = CanvasRepository.canvasFor(
            song(videoId = "", localUri = "file:///C:/music/x.flac"),
            spotifyFirst = false,
            chain = chain,
        )

        assertNull(found)
        assertEquals(0, fake.searches.size)
    }

    @Test
    fun `a blank title or artist is a miss without a request`() = runTest {
        val fake = FakeChain { _, _, _ -> artwork }
        val chain = fake.links("A")

        assertNull(CanvasRepository.canvasFor(song("blank-title", title = "  "), spotifyFirst = false, chain = chain))
        assertNull(CanvasRepository.canvasFor(song("blank-artist", artist = ""), spotifyFirst = false, chain = chain))
        assertEquals(0, fake.searches.size)
    }

    // ---- what the chain is actually asked ---------------------------------

    @Test
    fun `packaging is stripped before the chain sees the title`() = runTest {
        val fake = FakeChain { _, _, _ -> artwork }
        val chain = fake.links("A")

        CanvasRepository.canvasFor(
            song("packaging", title = "Crazy In Love (feat. JAY-Z) | Official Video"),
            spotifyFirst = false,
            chain = chain,
        )

        // The catalogue services never see YouTube's packaging; searching
        // with it finds nothing and matching against it rejects everything.
        // What the noise strip takes off is the tail after " | " and the
        // bracketed tags it names — a "(feat. ...)" credit stays, and is the
        // artist splitter's business, not the cleaner's.
        assertEquals("Crazy In Love (feat. JAY-Z)", fake.searches.single().second)
    }

    @Test
    fun `a source that throws is treated as one that found nothing`() = runTest {
        val fake = FakeChain { _, _, _ -> artwork }
        val throwing = ChainLink("boom") { _, _, _ -> error("the network is on fire") }

        val found = CanvasRepository.canvasFor(
            song("throwing"),
            spotifyFirst = false,
            chain = listOf(throwing) + fake.links("B"),
        )

        // None of these hosts are ours; a missing canvas is not worth
        // surfacing as an error — the next source still gets asked.
        assertNotNull(found)
        assertEquals(listOf("B"), fake.searches.map { it.first })
    }

    @Test
    fun `a wrong record's clip is rejected rather than adopted`() = runTest {
        val impostor = CanvasArtwork(url = "https://cdn.test/wrong.mp4", title = "Halo", artist = "Beyoncé")
        val fake = FakeChain { _, _, _ -> impostor }

        val found = CanvasRepository.canvasFor(
            song("impostor"),
            spotifyFirst = false,
            chain = fake.links("A", "B"),
        )

        // A near miss is a different song by the same artist — the one
        // failure mode users notice, and the re-check exists for it.
        assertNull(found)
        assertEquals(listOf("A", "B"), fake.searches.map { it.first })
    }

    // ---- serialisation -----------------------------------------------------

    @Test
    fun `concurrent lookups for one track collapse into one provider pass`() = runTest {
        val fake = FakeChain { _, _, _ -> artwork }
        val chain = fake.links("A", "B")

        // Both callers are released only once both are inside the chain's
        // first link, so the serialisation is what decides the count —
        // without the Mutex the second lookup would pass the gate too.
        val barrier = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.gate = barrier
        val lookups = (1..2).map {
            async {
                CanvasRepository.canvasFor(song("concurrent"), spotifyFirst = false, chain = chain)
            }
        }
        // The first caller is parked in the fake; the second must be parked
        // on the repository's Mutex, not in the provider. Give the first a
        // moment to arrive, then release everyone.
        kotlinx.coroutines.delay(100)
        barrier.complete(Unit)
        lookups.awaitAll()

        assertEquals(listOf("A"), fake.searches.map { it.first })
    }

    // ---- the production chain's own order ----------------------------------

    @Test
    fun `default order asks apple then tidal then community then spotify`() {
        assertEquals(
            listOf("AppleMusicCanvas", "TidalCanvas", "CommunityCanvas", "SpotifyCanvas"),
            CanvasRepository.songChain(spotifyFirst = false).map { it.name },
        )
    }

    @Test
    fun `the spotify-first order moves spotify to the front`() {
        assertEquals(
            listOf("SpotifyCanvas", "AppleMusicCanvas", "TidalCanvas", "CommunityCanvas"),
            CanvasRepository.songChain(spotifyFirst = true).map { it.name },
        )
    }

    @Test
    fun `the priority is part of the cache key`() = runTest {
        // Pinned through the cache itself: the same song resolved under one
        // order is not served to a caller asking under the other, because a
        // toggle never reuses the other order's answer.
        val fake = FakeChain { _, _, _ -> artwork }
        val chain = fake.links("A")

        CanvasRepository.canvasFor(song("order-key"), spotifyFirst = false, chain = chain)
        CanvasRepository.canvasFor(song("order-key"), spotifyFirst = true, chain = chain)

        assertEquals(2, fake.searches.size)
    }
}
