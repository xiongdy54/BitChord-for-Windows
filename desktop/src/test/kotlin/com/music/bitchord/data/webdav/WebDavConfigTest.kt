package com.music.bitchord.data.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The WebDAV configuration and the pure halves of the client — URL
 * normalization, the `webdav:` id scheme, the auth header, the multistatus
 * parser, the upload-name machinery. All no-network by construction; the
 * network paths themselves are upstream-verified shapes this port carries
 * verbatim and are not re-tested here (no mock-server infrastructure exists
 * on either side of the port).
 */
class WebDavConfigTest {

    // ---- URL normalization --------------------------------------------------------------

    @Test
    fun `normalizeUrl adds the scheme and trims trailing slashes`() {
        assertEquals("", WebDavConfig.normalizeUrl("   "))
        assertEquals("https://cloud.example.com/dav", WebDavConfig.normalizeUrl("cloud.example.com/dav/"))
        assertEquals("https://cloud.example.com", WebDavConfig.normalizeUrl("https://cloud.example.com/"))
        // The scheme's own case is the caller's spelling — the header and
        // request paths do not care, and rewriting it would surprise no one
        // who wrote it that way on purpose.
        assertEquals("HTTP://host/dav", WebDavConfig.normalizeUrl("HTTP://host/dav/"))
    }

    @Test
    fun `isConfigured demands a parseable host`() {
        assertTrue(WebDavConfig.isConfigured("https://cloud.example.com/dav/"))
        assertTrue(WebDavConfig.isConfigured("nas.local:5006/dav"))
        // "not a url" normalizes to "https://not a url", which no host answers for.
        assertFalse(WebDavConfig.isConfigured("not a url"))
        assertFalse(WebDavConfig.isConfigured(""))
    }

    @Test
    fun `hostOf lowercases and survives garbage`() {
        assertEquals("cloud.example.com", WebDavConfig.hostOf("https://Cloud.Example.COM/dav"))
        assertNull(WebDavConfig.hostOf("not a url"))
        assertNull(WebDavConfig.hostOf(""))
    }

    // ---- the id scheme -------------------------------------------------------------------

    @Test
    fun `ids are prefixed file urls, and round-trip`() {
        val url = "https://host/dav/Music/Artist%20-%20Title.mp3"
        assertEquals("webdav:$url", WebDavConfig.idFor(url))
        assertTrue(WebDavConfig.isWebDavId("webdav:$url"))
        assertFalse(WebDavConfig.isWebDavId(url))
        assertEquals(url, WebDavConfig.fileUrlOf("webdav:$url"))
        assertNull(WebDavConfig.fileUrlOf(url))
        assertNull(WebDavConfig.fileUrlOf("webdav:ftp://host/f.mp3"))
    }

    // ---- credentials ---------------------------------------------------------------------

    @Test
    fun `basic auth encodes both halves and skips the empty pair`() {
        assertNull(WebDavConfig.basicAuthHeader("", ""))
        val header = WebDavConfig.basicAuthHeader("user", "pa:ss")!!
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("user:pa:ss".toByteArray()), header)
    }

    @Test
    fun `the auth pool authorizes only its own host`() {
        WebDavAuth.update("https://Server.Example.com/dav/", "user", "pass")
        assertTrue(WebDavAuth.shouldAuthorize("server.example.com"))
        assertTrue(WebDavAuth.shouldAuthorize("SERVER.EXAMPLE.COM"))
        assertFalse(WebDavAuth.shouldAuthorize("other.example.com"))
        assertFalse(WebDavAuth.shouldAuthorize("server.example.com.evil.io"))
        assertEquals(WebDavConfig.basicAuthHeader("user", "pass"), WebDavAuth.authHeader)
    }

    // ---- audio names ----------------------------------------------------------------------

    @Test
    fun `the audio extension set is upstream's`() {
        for (ext in listOf("mp3", "m4a", "flac", "ogg", "opus", "aac", "wav", "wma", "aif", "aiff", "webm")) {
            assertTrue(WebDavConfig.isAudioFile("x.$ext"), "x.$ext")
        }
        assertFalse(WebDavConfig.isAudioFile("x.txt"))
    }

    // ---- the client's pure halves ---------------------------------------------------------

    @Test
    fun `resolveHref handles absolute, root-relative and bare hrefs`() {
        assertEquals(
            "https://host/dav/a.mp3",
            WebDavClient.resolveHref("https://host/dav/", "https://host/dav/a.mp3"),
        )
        assertEquals("https://host/dav/a.mp3", WebDavClient.resolveHref("https://host/dav/", "/dav/a.mp3"))
        assertEquals("https://host/dav/a.mp3", WebDavClient.resolveHref("https://host/dav/", "a.mp3"))
    }

    @Test
    fun `parseMultistatus drops the directory itself and reads entries`() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/Music/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/Music/Artist%20-%20Title.mp3</d:href>
                <d:propstat><d:prop><d:resourcetype/><d:getcontenttype>audio/mpeg</d:getcontenttype></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val entries = WebDavClient.parseMultistatus(xml, "https://host/dav/")
        assertEquals(2, entries.size)
        val collection = entries[0]
        assertTrue(collection.isCollection)
        assertEquals("https://host/dav/Music/", collection.url)
        val file = entries[1]
        assertFalse(file.isCollection)
        assertEquals("audio/mpeg", file.contentType)
        // Display name falls back to the decoded last segment.
        assertEquals("Artist - Title.mp3", file.displayName)
    }

    @Test
    fun `joinUrl percent-encodes the segment but keeps spaces literal`() {
        assertEquals("https://host/a/b%20c.mp3", WebDavClient.joinUrl("https://host/a", "b c.mp3"))
        assertEquals("https://host/a/x_y.mp3", WebDavClient.joinUrl("https://host/a", "x_y.mp3"))
    }

    @Test
    fun `uploadFileName sanitizes filesystem-hostile characters`() {
        assertEquals("Artist - Title.mp3", WebDavClient.uploadFileName("Title", "Artist", "mp3"))
        assertEquals("Title.mp3", WebDavClient.uploadFileName("Title", "Unknown Artist", "mp3"))
        assertEquals("C_D - A_B.mp3", WebDavClient.uploadFileName("A/B", "C\\D", "MP3"))
        assertEquals("track.mp3", WebDavClient.uploadFileName("   ...", "", ""))
    }

    @Test
    fun `numbered names dodge everything taken, case-insensitively`() {
        assertEquals("base.mp3", WebDavClient.resolveNumberedName("base", "mp3", emptySet()))
        assertEquals("base (1).mp3", WebDavClient.resolveNumberedName("base", "mp3", setOf("base.mp3")))
        assertEquals(
            "base (2).mp3",
            WebDavClient.resolveNumberedName("base", "mp3", setOf("base.mp3", "BASE (1).MP3")),
        )
    }

    // ---- repository mapping -----------------------------------------------------------------

    @Test
    fun `parent folder names the album`() {
        assertEquals(
            "Album",
            WebDavRepository.parentFolderName("https://host/dav/Music/Album/Artist%20-%20Title.mp3"),
        )
        // A file at the listing root takes the root's own name — upstream's
        // behaviour, which is why a bare `dav/files/user` listing still
        // groups rather than leaving every row album-less.
        assertEquals("dav", WebDavRepository.parentFolderName("https://host/dav/file.mp3"))
    }

    @Test
    fun `songFor builds a row the remote way`() {
        val song = WebDavConfig.songFor(
            "https://host/dav/Music/Album/Artist%20-%20Title.mp3",
            albumName = "Album",
        )
        assertEquals("webdav:https://host/dav/Music/Album/Artist%20-%20Title.mp3", song.videoId)
        assertEquals("Title", song.title)
        assertEquals("Artist", song.artist)
        assertEquals("Album", song.albumName)
        assertEquals("https://host/dav/Music/Album/Artist%20-%20Title.mp3", song.localUri)
        assertEquals(com.music.bitchord.data.model.PlaybackSourceType.BROWSE, song.playbackSourceType)
        assertEquals(WebDavConfig.BROWSE_ID, song.playbackSourceId)
    }

    // ---- RemoteSong's shared split ------------------------------------------------------------

    @Test
    fun `the filename split is RemoteSong's convention`() {
        assertEquals(Pair("Artist", "Title"), RemoteSongKt.split("Artist - Title"))
        assertEquals(Pair(null, "Title"), RemoteSongKt.split("Title"))
        assertEquals(Pair("A", "B - C"), RemoteSongKt.split("A - B - C"))
        assertEquals(Pair("A", "B"), RemoteSongKt.split(" A  -  B "))
        // A name that starts with the delimiter has no artist, however you squint.
        assertEquals(Pair(null, "Title"), RemoteSongKt.split(" - Title"))
    }
}

/** Indirection so the split table reads cleanly above. */
private object RemoteSongKt {
    fun split(base: String) = com.music.bitchord.data.remote.RemoteSong.splitArtistTitle(base)
}
