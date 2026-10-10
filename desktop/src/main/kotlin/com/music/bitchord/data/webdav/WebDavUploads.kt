// Ported from app/src/main/java/com/music/bitchord/data/webdav/WebDavUploads.kt.
//
// Desktop form, three divergences from the Android original, each forced by
// the platform rather than chosen for convenience:
//
//  * Sources are plain files. The `content://` branch and its resolver size
//    probe are gone — a track's `localUri` here is the absolute path itself
//    (the local scan's id convention) or a download's file path.
//  * The upload notifications are gone (design decision 9: no notification
//    substrate on desktop). The `active`/`conflict` flows are the whole
//    progress surface; the page that starts a batch draws it.
//  * The cover-top-up staging (`prepareUpload`) is not ported. Upstream
//    re-tags app downloads whose saved file lacked a cover, papering over a
//    save path that had no artwork in hand; the desktop downloader embeds
//    artwork at save time, so the case it served does not arise, and adding
//    a second tag-writing path here would duplicate a writer that already
//    runs where it belongs.
package com.music.bitchord.data.webdav

import com.music.bitchord.data.model.Song
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Copies on-device tracks to the configured WebDAV server. Copy, not move:
 * the local file stays where it was, so this never needs delete permission
 * and never rewrites the Downloads record — the server copy simply shows up
 * in the WebDAV library on its next listing.
 *
 * One batch runs sequentially on IO. Parallel PUTs would finish sooner on
 * Wi-Fi and fall over on a metered connection, and ordering is what makes a
 * multi-conflict batch answerable: each clash suspends on [conflict], the
 * page's dialog answers it, and an "apply to all" answer carries the rest
 * of the batch without another popup.
 */
object WebDavUploads {

    sealed interface TrackState {
        data object Queued : TrackState
        data class Running(val fraction: Float) : TrackState
        data object Done : TrackState
        data class Failed(val reason: String) : TrackState
        data object Skipped : TrackState
    }

    enum class Choice { OVERWRITE, KEEP_BOTH, SKIP }

    data class Resolution(val choice: Choice, val applyToAll: Boolean)

    data class ConflictRequest(
        val song: Song,
        val fileName: String,
        /** Tracks still queued behind this one — whether "apply to all" means anything. */
        val remaining: Int,
        val answer: CompletableDeferred<Resolution>,
    )

    data class Summary(val uploaded: Int, val skipped: Int, val failed: Int) {
        val total: Int get() = uploaded + skipped + failed
    }

    private val _active = MutableStateFlow<Map<String, TrackState>>(emptyMap())
    val active: StateFlow<Map<String, TrackState>> = _active.asStateFlow()

    /**
     * The clash waiting on the listener, or null when the batch is flowing.
     * Rendered by the page — the uploader itself is headless.
     */
    private val _conflict = MutableStateFlow<ConflictRequest?>(null)
    val conflict: StateFlow<ConflictRequest?> = _conflict.asStateFlow()

    /**
     * Whether [song] can be sent at all: a file on this machine, not a track
     * already living on the server and not a remote stream wearing a
     * `localUri`. Readability itself is checked at upload time and reported
     * per track — this is only the cheap gate for showing the row.
     */
    fun isUploadable(song: Song): Boolean {
        if (WebDavConfig.isWebDavId(song.videoId)) return false
        val uri = song.localUri ?: return false
        if (uri.contains("://")) return false
        return File(uri).isFile
    }

    suspend fun upload(songs: List<Song>): Summary = withContext(Dispatchers.IO) {
        val url = com.music.bitchord.data.settings.AppSettings.webdavUrl.value
        val username = com.music.bitchord.data.settings.AppSettings.webdavUsername.value
        val password = com.music.bitchord.data.settings.AppSettings.webdavPassword.value
        val root = WebDavConfig.normalizeUrl(url)
        require(WebDavConfig.isConfigured(root)) { "WebDAV is not configured" }

        val queue = songs.filter { isUploadable(it) }
        var uploaded = 0
        var skipped = 0
        var failed = 0
        var standing: Resolution? = null
        // Names claimed by this batch, so two locals with the same title
        // don't both aim at one target.
        val taken = HashSet<String>()

        if (queue.isEmpty()) return@withContext Summary(0, 0, 0)
        // Fresh state for the tracks about to run; anything left over from an
        // earlier batch for them (a Retry from a failure) is stale.
        setAll(queue.map { it.videoId }, null)
        for ((index, song) in queue.withIndex()) {
            set(song.videoId, TrackState.Queued)
            val remaining = queue.size - index - 1
            runCatching {
                uploadOne(song, root, username, password, taken, remaining) { req ->
                    standing?.let { return@uploadOne it }
                    _conflict.value = req
                    try {
                        req.answer.await().also { if (it.applyToAll) standing = it }
                    } finally {
                        _conflict.value = null
                    }
                }
            }.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        Outcome.Uploaded -> {
                            set(song.videoId, TrackState.Done)
                            uploaded++
                        }
                        Outcome.Skipped -> {
                            set(song.videoId, TrackState.Skipped)
                            skipped++
                        }
                    }
                },
                onFailure = {
                    set(song.videoId, TrackState.Failed(it.message ?: "Upload failed"))
                    failed++
                },
            )
        }
        Summary(uploaded, skipped, failed)
    }

    private enum class Outcome { Uploaded, Skipped }

    private suspend fun uploadOne(
        song: Song,
        root: String,
        username: String,
        password: String,
        taken: MutableSet<String>,
        remaining: Int,
        resolve: suspend (ConflictRequest) -> Resolution,
    ): Outcome {
        val file = File(song.localUri ?: throw WebDavException("Could not read this file"))
        if (!file.isFile || !file.canRead()) throw WebDavException("Could not read this file")
        val ext = extensionFor(song)
        return sendBytes(
            song = song,
            source = UploadSource(file.inputStream(), file.length().takeIf { it > 0 } ?: -1L),
            ext = ext,
            root = root,
            username = username,
            password = password,
            taken = taken,
            remaining = remaining,
            resolve = resolve,
        )
    }

    private suspend fun sendBytes(
        song: Song,
        source: UploadSource,
        ext: String,
        root: String,
        username: String,
        password: String,
        taken: MutableSet<String>,
        remaining: Int,
        resolve: suspend (ConflictRequest) -> Resolution,
    ): Outcome {
        val base = WebDavClient.uploadFileName(song.title, song.artist, ext)
            .removeSuffix(".$ext")
        // In-batch collisions are numbered without troubling the server.
        var fileName = WebDavClient.resolveNumberedName(base, ext, taken)
        taken.add(fileName.lowercase(Locale.ROOT))

        var target = WebDavClient.joinUrl(root, fileName)
        // Only an overwrite writes over an existing file; every other path
        // keeps `If-None-Match`, so a race after the check below 412s instead
        // of clobbering.
        var overwrite = false
        val alreadyThere = WebDavClient.exists(target, username, password).getOrThrow()
        if (alreadyThere) {
            when (resolve(ConflictRequest(song, fileName, remaining, CompletableDeferred())).choice) {
                Choice.SKIP -> { source.stream.close(); return Outcome.Skipped }
                Choice.OVERWRITE -> overwrite = true
                Choice.KEEP_BOTH -> {
                    // Number past whatever the server holds, checking each
                    // candidate — the batch set above only knows about itself.
                    var attempts = 0
                    while (attempts++ < 25) {
                        taken.add(fileName.lowercase(Locale.ROOT))
                        fileName = WebDavClient.resolveNumberedName(base, ext, taken)
                        target = WebDavClient.joinUrl(root, fileName)
                        if (!WebDavClient.exists(target, username, password).getOrThrow()) break
                    }
                    taken.add(fileName.lowercase(Locale.ROOT))
                }
            }
        }
        val (stream, length) = source
        when (
            WebDavClient.putFile(
                fileUrl = target,
                stream = stream,
                contentLength = length,
                mimeType = mimeFor(ext),
                username = username,
                password = password,
                overwrite = overwrite,
                onProgress = { written ->
                    if (length <= 0) return@putFile
                    val fraction = (written.toFloat() / length).coerceIn(0f, 1f)
                    set(song.videoId, TrackState.Running(fraction))
                },
            ).getOrThrow()
        ) {
            // Lost a race with something else writing the same name after the
            // check above. The bytes on the server aren't ours, so this is a
            // skip rather than a success.
            WebDavClient.PutResult.AlreadyExists -> return Outcome.Skipped
            WebDavClient.PutResult.Uploaded -> return Outcome.Uploaded
        }
    }

    private data class UploadSource(val stream: InputStream, val length: Long)

    /** The extension the file itself carries; the whitelist is the decoder of last resort. */
    internal fun extensionFor(song: Song): String {
        val fromPath = song.localUri
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in WebDavConfig.audioExtensions }
        return fromPath ?: "mp3"
    }

    private fun mimeFor(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }

    private fun set(videoId: String, state: TrackState?) {
        _active.value = _active.value.let { current ->
            if (state == null) current - videoId else current + (videoId to state)
        }
    }

    private fun setAll(videoIds: List<String>, state: TrackState?) {
        _active.value = _active.value.let { current ->
            var next = current
            for (id in videoIds) next = if (state == null) next - id else next + (id to state)
            next
        }
    }

    class WebDavException(message: String) : Exception(message)
}
