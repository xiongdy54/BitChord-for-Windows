package com.music.bitchord.data.canvas

import com.music.bitchord.data.AppFiles
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A small file cache for canvas clips — the desktop spelling of upstream's
 * Media3 `SimpleCache` (app `data/canvas/CanvasCache.kt`).
 *
 * Why this exists at all: a canvas clip *loops*. Without a cache, every loop
 * past the first re-downloads the whole video — the 40MB-per-song incident
 * upstream's note records is what that costs on a playlist, and nothing on
 * the desktop side makes it cheaper. MP4 clips (Tidal and the community
 * index, and Spotify's own) are a single progressive file, so a plain file
 * cache answers them: first play streams the URL directly while a background
 * copy lands on disk, and every loop after that reads the local file.
 *
 * HLS (Apple's motion artwork) is deliberately not cached: an `.m3u8` is a
 * playlist of segments the decoder re-fetches per loop, and caching it would
 * mean re-implementing Media3's segment store, which is well past what
 * decoration deserves. Those clips stream, at the cost of re-fetching their
 * segments each lap — an accepted v1 shape, recorded here rather than hidden:
 * Apple's clips are the rarest source in the chain, and the chain prefers
 * MP4 whenever it can.
 *
 * Capacity follows upstream's 150MB (`CanvasCache.kt:41`), evicted
 * least-recently-used by file time. Every operation is wrapped: the cache is
 * a pure optimisation, and any failure in it must degrade to "stream the
 * URL", never to an error the player sees.
 */
object CanvasCache {

    private const val TAG = "CanvasCache"

    /** Upstream `CanvasCache.kt:41`, verbatim. */
    private const val CACHE_LIMIT_BYTES = 150L * 1024 * 1024

    /** One worker: downloads land in order and eviction never races a copy. */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "canvas-cache").apply { isDaemon = true }
    }

    private val dir: File by lazy { AppFiles.dir("canvas") }

    /**
     * What the player should mount for [url]: the local file when the clip is
     * cached, [url] itself when it is not — with a background download asked
     * for either way, so the next loop of this track and the next play of it
     * come off the disk. Never blocks and never throws.
     *
     * The local answer is the file's absolute path: libvlc takes a plain path
     * as an MRL as happily as an `http(s)` one.
     */
    fun resolve(url: String): String {
        val file = cacheableFile(url) ?: return url
        runCatching {
            if (file.isFile && file.length() > 0) {
                // Touch, so "recently looped" is what survives an eviction.
                file.setLastModified(System.currentTimeMillis())
                Log.d(TAG, "cache hit for ${url.takeLast(48)}")
                return file.absolutePath
            }
        }
        enqueue(url, file)
        return url
    }

    /** A background copy for next time. Failures are silent: it is a cache. */
    private fun enqueue(url: String, target: File) {
        executor.execute {
            var part: File? = null
            runCatching {
                dir.mkdirs()
                // Written to a sibling temp name first, so a half-downloaded
                // clip can never be mistaken for a cached one, then moved
                // into place — same-directory rename, as atomic as Windows
                // makes it.
                val scratch = File(dir, "${target.name}.part-${System.nanoTime()}")
                part = scratch
                val request = okhttp3.Request.Builder().url(url).build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    body.byteStream().use { input ->
                        scratch.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (scratch.length() > 0) {
                    java.nio.file.Files.move(
                        scratch.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                    part = null
                    Log.d(TAG, "cached ${target.length() / 1024}KiB for ${url.takeLast(48)}")
                } else {
                    scratch.delete()
                    part = null
                }
                evict()
            }.onFailure {
                Log.d(TAG, "cache download failed: ${it.message}")
                // The half-written scratch file must not accumulate; the
                // finished one has already been renamed away by now.
                runCatching { part?.delete() }
            }
        }
    }

    /**
     * The file [url] caches to, or null when [url] is not cacheable at all.
     *
     * Internal rather than private for exactly one reason: the unit tests seed
     * and name files through it to pin the hit path, the MP4/HLS split and
     * the hash key. Production reaches it only through [resolve].
     */
    internal fun cacheableFile(url: String): File? = runCatching {
        if (!url.startsWith("http")) return null
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        // Only progressive files are cacheable; see the class doc for why
        // HLS is not. Keyed on the stripped path, so the same clip behind a
        // rotating query string is still one file.
        if (!path.endsWith(".mp4")) return null
        File(dir, sha256(path) + ".mp4")
    }.getOrNull()

    /**
     * Keeps [dir] under [limit], oldest files first. Internal with parameters
     * so the eviction order can be pinned on a scratch directory without
     * filling 150MB; production calls it after every landing with its own
     * directory and cap.
     */
    internal fun evict(dir: File = this.dir, limit: Long = CACHE_LIMIT_BYTES) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= limit) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= limit) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
