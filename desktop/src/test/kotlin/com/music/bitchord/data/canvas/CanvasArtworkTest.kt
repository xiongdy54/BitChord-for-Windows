package com.music.bitchord.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The strict-match table for [CanvasArtwork.matches] and the normalisation it
 * stands on — the exact judgements that keep a free-text catalogue search
 * from settling on a different song by the same artist. Every row here is a
 * mis-hit that was seen in the wild upstream, or the near miss it exists to
 * keep out.
 */
class CanvasArtworkTest {

    // ---- normalizeForMatch: case, accents and punctuation all fold away ----

    @Test
    fun `accents fold away through nfd`() {
        assertEquals("beyonce", "Beyoncé".normalizeForMatch())
        assertEquals("beyonce", "Beyoncé".normalizeForMatch())
        assertEquals("beyonce crazy in love", "Beyoncé CRAZY IN LOVE".normalizeForMatch())
    }

    @Test
    fun `punctuation becomes a space rather than vanishing`() {
        // The apostrophe spaces out rather than vanishing — "don't" and
        // "dont" stay different words, as they are.
        assertEquals("don t stop me now", "Don't Stop Me Now".normalizeForMatch())
        assertEquals("a b", "A-B".normalizeForMatch())
    }

    @Test
    fun `runs of punctuation and space collapse to one`() {
        assertEquals("a b", "A — B!!".normalizeForMatch())
        assertEquals("a b", "  A     b  ".normalizeForMatch())
    }

    @Test
    fun `the upstream example normalises equal on both sides`() {
        // app CanvasArtwork.kt:66 — "Beyoncé - CRAZY IN LOVE (feat. JAY-Z)"
        // against "Beyonce Crazy in Love feat Jay Z".
        assertEquals(
            "Beyoncé - CRAZY IN LOVE (feat. JAY-Z)".normalizeForMatch(),
            "Beyonce Crazy in Love feat Jay Z".normalizeForMatch(),
        )
    }

    // ---- splitArtists: every service picks its own separator ----

    @Test
    fun `ampersand and comma both split`() {
        assertEquals(listOf("a", "b"), splitArtists("A & B"))
        assertEquals(listOf("a", "b"), splitArtists("A, B"))
        assertEquals(listOf("a", "b", "c"), splitArtists("A, B & C"))
    }

    @Test
    fun `feature markers split`() {
        assertEquals(listOf("a", "jay z"), splitArtists("A feat. Jay Z"))
        assertEquals(listOf("a", "b"), splitArtists("A ft. B"))
        assertEquals(listOf("a", "b"), splitArtists("A featuring B"))
    }

    @Test
    fun `a bare x between collaborators splits, glued ones do not`() {
        assertEquals(listOf("a", "b"), splitArtists("A x B"))
        assertEquals(listOf("a", "b"), splitArtists("A × B"))
        // "AxB" is a word, not two artists — the separator has to have space
        // around it or it is part of a name.
        assertEquals(listOf("axb"), splitArtists("AxB"))
    }

    @Test
    fun `with splits`() {
        assertEquals(listOf("a", "b"), splitArtists("A with B"))
    }

    @Test
    fun `blank halves fall out`() {
        assertEquals(listOf("a"), splitArtists("A & "))
        assertEquals(emptyList(), splitArtists(" , & "))
    }

    // ---- matches: the re-check that keeps a wrong record's clip out ----

    @Test
    fun `exact title and artists match across case and accents`() {
        val clip = CanvasArtwork(url = "u", title = "Crazy In Love", artist = "Beyoncé")
        assertTrue(clip.matches("CRAZY IN LOVE", "beyonce", null))
    }

    @Test
    fun `a different song by the same artist is rejected`() {
        val clip = CanvasArtwork(url = "u", title = "Halo", artist = "Beyoncé")
        assertFalse(clip.matches("Crazy In Love", "Beyoncé", null))
    }

    @Test
    fun `every asked-for artist has to be credited`() {
        val clip = CanvasArtwork(url = "u", title = "Crazy In Love", artist = "Beyoncé")
        // The clip's own credit may fold several names into one string; what
        // matters is that the want side is fully covered.
        assertFalse(clip.matches("Crazy In Love", "Beyoncé & JAY-Z", null))
        val both = CanvasArtwork(url = "u", title = "Crazy In Love", artist = "Beyoncé & JAY-Z")
        assertTrue(both.matches("Crazy In Love", "Beyoncé, JAY-Z", null))
    }

    @Test
    fun `an unknown album on either side does not sink the match`() {
        val clip = CanvasArtwork(url = "u", title = "Crazy In Love", artist = "Beyoncé", album = null)
        assertTrue(clip.matches("Crazy In Love", "Beyoncé", "B'Day"))
        val credited = CanvasArtwork(url = "u", title = "Crazy In Love", artist = "Beyoncé", album = "B'Day")
        // The track queued from search may never get an album name; holding
        // the clip to one would mean no canvas at all for most of a session.
        assertTrue(credited.matches("Crazy In Love", "Beyoncé", null))
    }

    @Test
    fun `a known album that disagrees is rejected`() {
        val clip = CanvasArtwork(url = "u", title = "Crazy In Love", artist = "Beyoncé", album = "I Am... Sasha Fierce")
        assertFalse(clip.matches("Crazy In Love", "Beyoncé", "B'Day"))
        assertTrue(clip.matches("Crazy In Love", "Beyoncé", "i am sasha fierce"))
    }

    @Test
    fun `a blank want title or artist defers to the clip`() {
        // A caller with nothing to compare against does not get a rejection
        // out of its own blank metadata.
        val clip = CanvasArtwork(url = "u", title = "Crazy In Love", artist = "Beyoncé")
        assertTrue(clip.matches("", "Beyoncé", null))
        assertTrue(clip.matches("Crazy In Love", "", null))
    }

    @Test
    fun `a clip with no metadata to check cannot be rejected by it`() {
        val anonymous = CanvasArtwork(url = "u")
        assertTrue(anonymous.matches("Anything", "Anyone", "Any Album"))
    }
}
