// Ported from app/src/main/java/com/music/bitchord/data/remote/RemoteArtworkStore.kt.
//
// The Android surface collapses to the desktop's own: `Context` becomes
// [com.music.bitchord.data.AppFiles]'s cache dir, and `Uri.fromFile` becomes
// `File.toURI()` — the same `file:` scheme Coil's file fetcher reads. The
// extraction target widens by one case: a track whose `localUri` is a plain
// filesystem path (the local scan's id convention) extracts through the local
// reader, so an on-device row with no sidecar cover lights up the same way a
// WebDAV one does.
package com.music.bitchord.data.remote

import com.music.bitchord.data.AppFiles
import com.music.bitchord.data.model.Song
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Embedded covers for remote tracks, extracted lazily and cached.
 *
 * Listings never touch audio bytes, so a track whose art lives inside the
 * file arrives with no thumbnail. The first surface that wants to draw it —
 * a row, an album card, the player — asks here instead: memory first, then
 * a disk cache of extracted pictures, and only then the bounded ranged
 * reads that pull the picture out of the file. Concurrent asks for one
 * track share a single extraction.
 *
 * Disk names are content-blind hashes of the track id: ids embed full URLs
 * with slashes and escapes (and, for the local scan, whole paths) that must
 * never reach the filesystem. A server-side overwrite keeps serving the
 * stale copy — accepted, since revalidating would cost a request per row
 * per list.
 */
object RemoteArtworkStore {

    private const val MAX_DIR_BYTES = 100L * 1024 * 1024

    private val memory = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val claimed = ConcurrentHashMap.newKeySet<String>()
    private val inFlightLock = Mutex()

    /**
     * A drawable art URI for [song]: its own thumbnail when it has one, else
     * the extracted embedded picture, else null. Never throws.
     */
    suspend fun resolveArt(song: Song): String? {
        song.thumbnailUrl?.let { return it }
        val key = song.videoId
        memory[key]?.let { return it }
        diskHit(key)?.let {
            memory[key] = it
            return it
        }
        val job = inFlightLock.withLock {
            inFlight.getOrPut(key) { CompletableDeferred() }
        }
        if (claimed.add(key)) {
            try {
                job.complete(extractAndStore(song, key))
            } catch (e: Throwable) {
                job.completeExceptionally(e)
                throw e
            } finally {
                claimed.remove(key)
                inFlightLock.withLock {
                    inFlight.remove(key, job)
                }
            }
        }
        return runCatching { job.await() }.getOrNull()?.also { memory[key] = it }
    }

    private suspend fun extractAndStore(song: Song, key: String): String? =
        withContext(Dispatchers.IO) {
            val address = song.localUri?.takeIf { it.startsWith("http") || it.startsWith("smb://") || isLocalFilePath(it) }
                ?: return@withContext null
            val picture = RemoteArtReader.picture(address, address.startsWith("smb://"))
                ?: return@withContext null
            val dir = File(AppFiles.cacheDir, "remote_art").apply { mkdirs() }
            val file = File(dir, "${cacheKey(key)}.${extensionFor(picture.mime)}")
            runCatching {
                file.outputStream().use { it.write(picture.bytes) }
                evict(dir)
            }.getOrNull() ?: return@withContext null
            // `File.toURI()` spells the path `file:/C:/…` — one slash after the
            // scheme — and Coil's Uri parser cannot name a fetcher for that
            // shape. `Path.toUri()` spells the same file `file:///C:/…`, which
            // Coil reads; the difference is the whole local-cover bug.
            file.toPath().toUri().toString()
        }

    /** A local scan row's address is the absolute path itself — no scheme on it. */
    private fun isLocalFilePath(address: String): Boolean =
        !address.contains("://") && File(address).isFile

    private fun diskHit(key: String): String? {
        val dir = File(AppFiles.cacheDir, "remote_art")
        if (!dir.isDirectory) return null
        // The extension depends on the sniffed mime, so match by stem.
        val match = dir.listFiles { _, name -> name.startsWith("${cacheKey(key)}.") }
            ?.filter { it.isFile && it.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
        return match?.let { it.toPath().toUri().toString() }
    }

    private fun extensionFor(mime: String): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        else -> "bin"
    }

    private fun cacheKey(videoId: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        return digest.digest(videoId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun evict(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DIR_BYTES) return
        files.sortedBy { it.lastModified() }.forEach {
            if (total <= MAX_DIR_BYTES) return
            total -= it.length()
            it.delete()
        }
    }
}
