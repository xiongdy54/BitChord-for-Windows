package com.music.bitchord.desktop.playback

import com.music.bitchord.data.webdav.WebDavAuth
import com.music.bitchord.data.webdav.WebDavConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The resolve seam's two new branches, as a table — the local path's id *is*
 * the file, and a `webdav:` id is its URL — plus the VLC Basic-auth mapping
 * that carries the credential the rest of the app puts in a header.
 *
 * The network branches (a YouTube id falling through to the download record
 * and the resolver) are deliberately absent: this table pins the two branches
 * that answer without a network, and leaves the resolver's behaviour to the
 * existing PlayerControllerTest, which stubs the whole seam.
 */
class LocalPlaybackResolveTest {

    private fun tempFile(name: String): File =
        File(java.nio.file.Files.createTempDirectory("resolve").toFile(), name).apply { writeBytes(ByteArray(8)) }

    private fun controller() = PlayerController(
        kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        FakeAudioEngine(),
    )

    @Test
    fun `an id that names an existing file resolves to itself`() = runBlocking(Dispatchers.Unconfined) {
        val file = tempFile("song.mp3")
        assertEquals(file.absolutePath, controller().resolveUrl(file.absolutePath))
    }

    @Test
    fun `a webdav id resolves to the file url it embeds`() = runBlocking(Dispatchers.Unconfined) {
        val url = "https://host/dav/song.mp3"
        assertEquals(url, controller().resolveUrl(WebDavConfig.idFor(url)))
    }

    @Test
    fun `a vanished local path does not resolve to itself`() {
        // The guard is `File.isFile` — a moved or deleted folder must fall
        // through to the ordinary resolve rather than hand the engine a
        // nothing. The fall-through itself is the network's business; here we
        // only pin that the local branch did not answer.
        val missing = File(java.nio.file.Files.createTempDirectory("resolve").toFile(), "gone.mp3")
        assertNull(controller().resolveLocalFile(missing.absolutePath))
    }

    @Test
    fun `a bare youtube id is not a file path`() {
        assertNull(controller().resolveLocalFile("dQw4w9WgXcQ"))
    }

    // ---- the VLC Basic-auth mapping -----------------------------------------------------

    @Test
    fun `a Basic header becomes MRL userinfo, encoded`() {
        val auth = WebDavConfig.basicAuthHeader("user", "pa:ss")!!
        val mrl = withBasicAuth("https://host/dav/a%20b.mp3", mapOf("Authorization" to auth))
        assertEquals("https://user:pa%3Ass@host/dav/a%20b.mp3", mrl)
    }

    @Test
    fun `urls and headers that need no mapping pass through`() {
        val url = "https://host/dav/a.mp3"
        assertEquals(url, withBasicAuth(url, emptyMap()))
        assertEquals(url, withBasicAuth(url, mapOf("Authorization" to "Bearer xyz")))
        // Local paths and anything without a scheme are not an HTTP MRL.
        assertEquals("C:\\music\\a.mp3", withBasicAuth("C:\\music\\a.mp3", mapOf("Authorization" to "Basic abc")))
        // A URL that already carries credentials is never rewritten.
        assertEquals("https://u:p@host/a.mp3", withBasicAuth("https://u:p@host/a.mp3", mapOf("Authorization" to "Basic abc")))
        // A payload that does not decode is not a credential.
        assertEquals(url, withBasicAuth(url, mapOf("Authorization" to "Basic %%%not-base64%%$")))
    }

    @Test
    fun `the mapping reflects the same credential pool the interceptor reads`() {
        WebDavAuth.update("https://host/dav", "user", "secret")
        val mrl = withBasicAuth("https://host/dav/a.mp3", mapOf("Authorization" to WebDavAuth.authHeader!!))
        assertEquals("https://user:secret@host/dav/a.mp3", mrl)
    }
}
