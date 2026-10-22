package com.music.bitchord.data.remote

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.music.bitchord.data.model.Song
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The local-cover seam, end to end: a FLAC with a picture block resolves to a
 * URI, and the image library the app actually uses can load that URI.
 *
 * The bug this pins: the store used to hand Coil `File.toURI()`'s
 * `file:/C:/…` — one slash after the scheme — and Coil 3.6.3's desktop
 * components cannot name a fetcher for that spelling. Every local cover
 * rendered as a placeholder while extraction itself worked. `Path.toUri()`
 * spells it `file:///C:/…`, which Coil fetches.
 */
class RemoteArtworkStoreTest {

    private fun u32be(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun block(type: Int, payload: ByteArray, last: Boolean) = byteArrayOf(
        ((if (last) 0x80 else 0) or type).toByte(),
        ((payload.size ushr 16) and 0xFF).toByte(),
        ((payload.size ushr 8) and 0xFF).toByte(),
        (payload.size and 0xFF).toByte(),
    ) + payload

    /** A 1×1 JPEG — enough for Coil's decoder to prove the fetch reached an image. */
    private val jpeg: ByteArray = Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a" +
            "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA" +
            "AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
    )

    private fun flacWithCover(): File {
        val mime = "image/jpeg".toByteArray()
        val picture = u32be(3) + u32be(mime.size) + mime + u32be(0) + ByteArray(16) +
            u32be(jpeg.size) + jpeg
        val file = kotlin.io.path.createTempFile("cover", ".flac").toFile().apply { deleteOnExit() }
        file.writeBytes(
            "fLaC".toByteArray() +
                block(0, ByteArray(34), last = false) +
                block(6, picture, last = true),
        )
        return file
    }

    @Test
    fun `a local cover resolves to a uri coil can load`() = runBlocking {
        val file = flacWithCover()
        val uri = requireNotNull(
            RemoteArtworkStore.resolveArt(
                Song(
                    videoId = file.absolutePath,
                    title = "Test Song",
                    artist = "Test Artist",
                    thumbnailUrl = null,
                    localUri = file.absolutePath,
                ),
            ),
        )
        assertTrue(uri.startsWith("file:///"), "unexpected URI shape: $uri")

        val context = PlatformContext.INSTANCE
        val result = ImageLoader.Builder(context).build().execute(
            ImageRequest.Builder(context).data(uri).build(),
        )
        assertTrue(result is SuccessResult, "Coil could not load $uri: $result")
    }
}
