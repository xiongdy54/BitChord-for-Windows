// Ported from app/src/main/java/com/music/bitchord/data/remote/RemoteArtReader.kt.
//
// The HTTP backend is verbatim. The SMB backend is deliberately absent — SMB
// is its own slice on desktop (design decision 1), and `smbj` is not on this
// module's classpath. Added here instead: `local`, the same Reader contract
// over a `RandomAccessFile`, which is what lets the on-device scan reuse the
// embedded-artwork parser its remote cousins share.
package com.music.bitchord.data.remote

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

/**
 * [EmbeddedArt.Reader] backends, one per file protocol. All stay dumb byte
 * fetchers — all format knowledge lives in [EmbeddedArt].
 */
object RemoteArtReader {

    /**
     * Reads [url] over HTTP byte ranges through the shared client, so the
     * server's auth interceptor applies exactly as it does for playback.
     * Servers that ignore Range answer 200 with the whole file; the read is
     * sliced down rather than failing, because a cover at the front is still
     * reachable that way.
     */
    fun http(url: String): EmbeddedArt.Reader = object : EmbeddedArt.Reader {
        override val size: Long = Long.MAX_VALUE

        override fun read(offset: Long, length: Int): ByteArray {
            if (length <= 0 || offset < 0) return ByteArray(0)
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=$offset-${offset + length - 1}")
                .get()
                .build()
            Http.client.newCall(request).execute().use { response ->
                if (response.code != 206 && response.code != 200) {
                    throw java.io.IOException("Range read failed with ${response.code}")
                }
                if (response.code == 200 && offset > 0) {
                    // No range support: only the front is usable.
                    return ByteArray(0)
                }
                val body = response.body ?: return ByteArray(0)
                // Bounded either way: a 200 answer carries the whole file,
                // and only the requested window is ever kept.
                val buffer = okio.Buffer()
                var remaining = length.toLong()
                body.source().use { source ->
                    while (remaining > 0) {
                        val read = source.read(buffer, remaining)
                        if (read == -1L) break
                        remaining -= read
                    }
                }
                return buffer.readByteArray()
            }
        }
    }

    /**
     * Reads the file at [path] with bounded offset reads — the local-disk
     * twin of [http]. The handle opens on the first read and closes with the
     * reader: one handle per extraction, never shared across threads, the
     * same discipline the SMB backend upstream keeps.
     */
    fun local(path: String): EmbeddedArt.Reader = object : EmbeddedArt.Reader {
        override val size: Long = File(path).length()

        private var file: RandomAccessFile? = null

        private fun handle(): RandomAccessFile {
            file?.let { return it }
            return RandomAccessFile(path, "r").also { file = it }
        }

        override fun read(offset: Long, length: Int): ByteArray {
            if (length <= 0 || offset < 0 || offset >= size) return ByteArray(0)
            val handle = handle()
            handle.seek(offset)
            val buf = ByteArray(length)
            var at = 0
            while (at < length) {
                val read = handle.read(buf, at, length - at)
                if (read <= 0) break
                at += read
            }
            return if (at == length) buf else buf.copyOf(at)
        }

        override fun close() {
            runCatching { file?.close() }
            file = null
        }
    }

    /**
     * Extracts the embedded picture at [url], or null when there is none.
     *
     * [isSmb] rides along for signature parity with upstream, where the SMB
     * backend selects itself on it; on desktop only the two backends above
     * exist, so an `smb://` address simply reads as no picture until its own
     * slice arrives.
     */
    suspend fun picture(url: String, isSmb: Boolean = false): EmbeddedArt.Picture? =
        withContext(Dispatchers.IO) {
            val reader = when {
                isSmb || url.startsWith("smb://") -> null
                url.startsWith("http") -> http(url)
                else -> local(url)
            } ?: return@withContext null
            try {
                runCatching { EmbeddedArt.extract(reader) }.getOrNull()
            } finally {
                runCatching { reader.close() }
            }
        }
}
