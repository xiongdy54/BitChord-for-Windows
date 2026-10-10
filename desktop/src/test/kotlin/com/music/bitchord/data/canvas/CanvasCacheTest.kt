package com.music.bitchord.data.canvas

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The file cache's own contract: what is cacheable at all (progressive MP4,
 * never HLS), the hit that answers with a local path, and the LRU eviction
 * that keeps the directory under the cap. The cache's directory resolves
 * under the test run's own app-data root, so seeding and sweeping it leaves
 * nothing behind that matters.
 */
class CanvasCacheTest {

    private val mp4Url = "https://resources.tidal.com/videos/aa/bb/cc/dd/ee/1280x1280.mp4"

    // ---- what is cacheable -------------------------------------------------

    @Test
    fun `an mp4 url maps to one stable hashed file`() {
        val file = CanvasCache.cacheableFile(mp4Url)
        assertNotNull(file)
        // The key is the URL's SHA-256 — the same URL always lands on the
        // same file, different URLs never share one.
        assertEquals(file, CanvasCache.cacheableFile(mp4Url))
        assertEquals(file, CanvasCache.cacheableFile("$mp4Url?token=x"))
        assertTrue(file.name.endsWith(".mp4"))
        assertTrue(file.name !in mp4Url, "the key must be hashed, not derived from the path")
    }

    @Test
    fun `hls is never cached — the segments are re-fetched per loop instead`() {
        assertNull(CanvasCache.cacheableFile("https://cdn.apple.com/motion/master.m3u8"))
        assertNull(CanvasCache.cacheableFile("https://cdn.apple.com/motion/master.M3U8"))
    }

    @Test
    fun `non-http urls are never cached`() {
        assertNull(CanvasCache.cacheableFile("file:///C:/video/x.mp4"))
        assertNull(CanvasCache.cacheableFile("rtsp://host/stream"))
    }

    // ---- the hit path --------------------------------------------------------

    @Test
    fun `a cached clip resolves to its local path`() {
        val file = CanvasCache.cacheableFile(mp4Url)
        assertNotNull(file)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(1024))
        try {
            val resolved = CanvasCache.resolve(mp4Url)
            assertEquals(file.absolutePath, resolved)
            assertTrue(file.length() > 0, "the cached copy must be intact")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `an uncached clip resolves to the remote url`() {
        // .invalid never resolves, so the background copy this asks for fails
        // fast and silently — which is the miss path's whole contract: the
        // URL is streamed now, the disk is populated for next time.
        val url = "https://cache.invalid/clip.mp4"
        assertEquals(url, CanvasCache.resolve(url))
        assertNull(CanvasCache.cacheableFile(url)?.takeIf { it.isFile })
    }

    @Test
    fun `an empty file is not a cache hit`() {
        val file = CanvasCache.cacheableFile(mp4Url)
        assertNotNull(file)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(0))
        try {
            // A zero-byte "copy" is a half-written one wearing a finished
            // name; it must not be answered with.
            assertEquals(mp4Url, CanvasCache.resolve(mp4Url))
        } finally {
            file.delete()
        }
    }

    // ---- eviction --------------------------------------------------------

    private fun seed(dir: File, name: String, size: Long, ageMs: Long): File {
        val file = File(dir, name)
        file.writeBytes(ByteArray(size.toInt()))
        assertTrue(file.setLastModified(System.currentTimeMillis() - ageMs))
        return file
    }

    @Test
    fun `eviction keeps the most recent files under the cap`() {
        val dir = kotlin.io.path.createTempDirectory("canvas-evict").toFile()
        try {
            // 4 files of 100 bytes, a 250-byte cap: the two oldest go.
            val oldest = seed(dir, "oldest.mp4", 100, 40_000)
            val older = seed(dir, "older.mp4", 100, 30_000)
            val newer = seed(dir, "newer.mp4", 100, 20_000)
            val newest = seed(dir, "newest.mp4", 100, 10_000)

            CanvasCache.evict(dir, limit = 250)

            assertTrue(!oldest.exists(), "the least recently used file goes first")
            assertTrue(!older.exists())
            assertTrue(newer.exists())
            assertTrue(newest.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `eviction stops as soon as the cap is met`() {
        val dir = kotlin.io.path.createTempDirectory("canvas-evict-partial").toFile()
        try {
            val oldest = seed(dir, "a.mp4", 100, 30_000)
            val middle = seed(dir, "b.mp4", 100, 20_000)
            val newest = seed(dir, "c.mp4", 100, 10_000)

            // 300 bytes, cap 200: one file has to go, and it is the oldest —
            // the rest are kept even though deleting more would leave margin.
            CanvasCache.evict(dir, limit = 200)

            assertTrue(!oldest.exists())
            assertTrue(middle.exists())
            assertTrue(newest.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a directory already under the cap is left alone`() {
        val dir = kotlin.io.path.createTempDirectory("canvas-evict-none").toFile()
        try {
            val file = seed(dir, "keep.mp4", 10, 60_000)
            CanvasCache.evict(dir, limit = 150)
            assertTrue(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
