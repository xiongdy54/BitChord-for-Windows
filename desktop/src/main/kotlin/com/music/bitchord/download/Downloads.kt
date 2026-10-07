// Ported from app/src/main/java/com/music/bitchord/download/Downloads.kt.
//
// The queue, the record and the transfer pipeline are the app's, line for line
// where they could be. What the desktop changes, all of it mechanical:
//   - No Context and no `android.net.Uri`: a download's address is a plain
//     absolute path, which is what the file system and the audio engine both
//     eat. `saved` maps videoId → path.
//   - No DownloadService: the drain is [drainQueue] below, the service's
//     worker loop verbatim over an internal scope — four workers, the same
//     idle grace, started by the first enqueue exactly as the service was.
//   - No configured-source route: the desktop has no SourceResolver layer, so
//     [routeFor] is YouTube's branch only, and with it go the packaged
//     (OfflineDASH/HLS) routes and the `exportDownloads` special cases.
//   - No collections: a batch download's grouping record had no surface to
//     serve this slice (release pages' own download button is not ported), so
//     `SavedCollection`/`DownloadTarget` and their persist key stay on the app
//     side until that UI arrives.
//   - No Wi-Fi gate and no legacy badge: the desktop has no metered concept
//     and no MediaMetadataRetriever-era downloads to migrate.
//   - The record persists to its own properties file, not the shared settings
//     one — a second FileStore over `settings.properties` would clobber
//     whatever AppSettings writes there (see Main.kt's note on the same trap).
package com.music.bitchord.download

import com.music.bitchord.data.AppFiles
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.FileStore
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.DownloadQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream

/** Where a track is between "not on this device" and "on it". */
sealed interface DownloadState {

    /** Accepted, waiting for the one in front of it. */
    data object Queued : DownloadState

    /** [fraction] is 0f until the length is known, which is the first thing asked for. */
    data class Running(val fraction: Float) : DownloadState

    data class Failed(val reason: String) : DownloadState
}

/**
 * The download queue, and the record of what came out of it.
 *
 * Split deliberately into two pieces of state that look similar and behave
 * nothing alike:
 *
 *  - [active] is what is happening now — queued, running, just failed. It lives
 *    in memory and is empty on a cold start, because a download interrupted by
 *    the process dying did not happen.
 *  - [saved] is what exists on disk, keyed by videoId and remembered across
 *    launches. It is the only way the app can answer "do I already have this?"
 *    without a directory walk per row, and the only way it knows *which* file a
 *    track corresponds to when asked to delete it.
 *
 * [saved] is a claim about a folder this app does not police. The user is free
 * to empty it, so an entry here can outlive the file it names — which is why
 * every read of it goes through [savedUri], and why that verifies before it
 * answers.
 */
object Downloads {

    private const val TAG = "BitChord"
    private const val KEY_SAVED = "downloaded_tracks"
    private const val KEY_SAVED_METADATA = "downloaded_tracks_metadata"

    private val prefs: FileStore by lazy { FileStore(AppFiles.file("downloads.properties")) }
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val metadataSerializer = MapSerializer(String.serializer(), SavedSongMetadata.serializer())

    private val _active = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val active: StateFlow<Map<String, DownloadState>> = _active.asStateFlow()

    private val _saved = MutableStateFlow(
        runCatching {
            json.decodeFromString(serializer, prefs.getString(KEY_SAVED, "{}"))
        }.getOrDefault(emptyMap()),
    )

    /** videoId to the path of the file saved for it. */
    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()

    private val _savedMetadata = MutableStateFlow(
        runCatching {
            json.decodeFromString(metadataSerializer, prefs.getString(KEY_SAVED_METADATA, "{}"))
        }.getOrDefault(emptyMap()),
    )

    /** Waiting, in the order asked for. Guarded by [lock]. */
    private val pending = LinkedHashMap<String, Song>()

    private val lock = Any()

    /**
     * The tracks taken off the queue and not yet finished, to the job fetching
     * each — null in the gap between a worker claiming a track and its job
     * existing.
     *
     * A map rather than the single slot this used to be, because several
     * downloads run at once now. That plurality is the only reason it is here:
     * [cancel] has to find *this* track's job among several, and a worker
     * claiming the next track must not be able to step on another worker's.
     * Guarded by [lock].
     */
    private val running = LinkedHashMap<String, Job?>()

    // ---- Asking -------------------------------------------------------------

    /**
     * Queue [song], and make sure something is draining the queue.
     *
     * A track already queued or running is left alone rather than doubled — the
     * menu row shows which of those it is, but a second tap before the menu
     * updates should still be a no-op. (One already *saved* is the caller's
     * question: the menu row offers deletion there instead of this door.)
     *
     * The first enqueue of a batch is what starts the drain, exactly as the
     * first enqueue started the service upstream — [ensureDraining] re-arms on
     * the next batch, and a worker's idle grace covers the gap the service's
     * start-up latency used to.
     */
    fun enqueue(song: Song, from: String? = null) {
        val id = song.videoId
        synchronized(lock) {
            if (id in pending || id in running) return
            pending[id] = song
        }
        _active.update { it + (id to DownloadState.Queued) }
        DownloadSession.queued(song, from)
        ensureDraining()
    }

    /**
     * Drop [videoId] from the queue, or stop it if it is one of the ones
     * running.
     *
     * Dropping it from [running] is what makes this safe in the gap between a
     * track being dequeued and its job existing: a cancel landing in that
     * window finds no job to stop, but [onRunning] then finds the id it was
     * told to run is no longer wanted, and stops it on arrival.
     */
    fun cancel(videoId: String) {
        val job = synchronized(lock) {
            pending.remove(videoId)
            if (videoId !in running) return@synchronized null
            running.remove(videoId)
        }
        job?.cancel()
        clear(videoId)
        // A download the user called off is not something they need reminding to
        // check on, so it leaves the manager rather than sitting in it as a
        // permanent "cancelled" row.
        DownloadSession.forget(videoId)
    }

    // ---- The record ---------------------------------------------------------

    /**
     * The file saved for [videoId], or null — pruning the record if the file
     * has been deleted from under it.
     *
     * Touches the filesystem, so call it off the main thread.
     */
    suspend fun savedUri(videoId: String): String? = withContext(Dispatchers.IO) {
        val recorded = _saved.value[videoId] ?: return@withContext null
        if (DownloadStore.exists(recorded)) return@withContext recorded
        Log.d(TAG, "$videoId was downloaded but the file is gone; forgetting it")
        forget(videoId)
        null
    }

    /**
     * True when [path] names a file that is not there.
     *
     * Deliberately cheap: this is called from the player's resolve seam, which
     * runs once per track transition. A stat is a few microseconds; the answer
     * is what stops the engine being handed a path that is simply not there.
     *
     * False for anything unreadable, which keeps "I could not tell" out of the
     * "the file is missing" answer — the caller drops a record on a true here.
     */
    fun isMissingLocalFile(path: String): Boolean =
        !runCatching { File(path).exists() }.getOrDefault(false)

    /**
     * As [savedUri], but synchronous — for the player's resolve seam, which has
     * neither a suspend context nor a reason to pay for one.
     *
     * Without this, a record surviving the file it names — a folder emptied by
     * hand — would send the engine a path to a file that is not there, and the
     * track would refuse to play with nothing to say why.
     *
     * Prunes the record on the way past, the same as [savedUri]: a claim that
     * has just been shown to be false is not worth keeping to be shown false
     * again on the next play.
     */
    fun verifiedSavedUri(videoId: String): String? {
        val recorded = _saved.value[videoId] ?: return null
        if (!isMissingLocalFile(recorded)) return recorded
        Log.d(TAG, "$videoId was downloaded but the file is gone; forgetting it")
        forget(videoId)
        return null
    }

    /** Delete the file saved for [videoId] and forget it. */
    suspend fun delete(videoId: String): Boolean = withContext(Dispatchers.IO) {
        val path = _saved.value[videoId] ?: return@withContext false
        val deleted = DownloadStore.delete(path)
        forget(videoId)
        deleted
    }

    private fun forget(videoId: String) {
        record(saved = { it - videoId }, meta = { it - videoId })
    }

    /**
     * Drop the record for [videoId] because a read of the file it names has
     * just failed.
     *
     * The public counterpart to [forget], for the player's failure path — the
     * one caller that does not need to check anything first, because the player
     * has already done better than a check: it opened the file and got an
     * error.
     *
     * Named for what it asserts rather than what it does, so a caller that has
     * *not* established the file is missing has no business calling it.
     */
    fun forgetMissing(videoId: String) {
        if (videoId !in _saved.value) return
        Log.d(TAG, "$videoId could not be opened; forgetting the download")
        forget(videoId)
    }

    /**
     * Record one file under every id it could be asked about.
     *
     * [asked] is the row the user tapped and [fetched] is what was actually
     * downloaded, and the two can differ when a resolve substitutes a track.
     * Filing it under both is what lets the same song, found later through
     * search, still know it is already on the device. A stale id costs nothing:
     * the verification in [savedUri] prunes whichever one stops resolving.
     */
    private fun remember(
        asked: Song,
        fetched: Song,
        path: String,
        downloadFormat: String? = null,
        artworkPath: String? = null,
    ) {
        val ids = setOf(asked.videoId, fetched.videoId)
        // Keep the creation time in our own record so "Date added" remains
        // stable if a file is subsequently retagged or otherwise modified. An
        // adopted file predating this field falls back to its last-modified
        // time.
        val fileModifiedMillis = File(path).lastModified().takeIf { it > 0 }
        val existingAdded = ids.firstNotNullOfOrNull { _savedMetadata.value[it]?.dateAddedSeconds }
        val dateAddedSeconds = resolvedDownloadDates(existingAdded, fileModifiedMillis).first
            ?: System.currentTimeMillis() / 1_000
        // Either row may be the one that knew the release.
        val album = fetched.albumName?.takeIf { it.isNotBlank() }
            ?: asked.albumName?.takeIf { it.isNotBlank() }
        val metaAsked = SavedSongMetadata(
            videoId = asked.videoId,
            title = asked.title,
            artist = asked.artist,
            thumbnailUrl = artworkPath ?: asked.thumbnailUrl,
            durationText = asked.durationText,
            albumName = album,
            uri = path,
            downloadFormat = downloadFormat,
            dateAddedSeconds = dateAddedSeconds,
        )
        val metaFetched = SavedSongMetadata(
            videoId = fetched.videoId,
            title = fetched.title,
            artist = fetched.artist,
            thumbnailUrl = artworkPath ?: fetched.thumbnailUrl,
            durationText = fetched.durationText,
            albumName = album,
            uri = path,
            downloadFormat = downloadFormat,
            dateAddedSeconds = dateAddedSeconds,
        )
        record(
            saved = { it + ids.associateWith { path } },
            meta = {
                it + mapOf(asked.videoId to metaAsked, fetched.videoId to metaFetched)
            },
        )
    }

    /**
     * Apply [saved] and [meta] to the two records and write the result down.
     *
     * Takes transforms rather than finished maps because several downloads
     * finish at once now, and "read the map, add my track, store it back" run
     * from two threads loses one of the two tracks — silently, and permanently,
     * since this is the only record that a file was written. Both flows are
     * updated compare-and-set, and the persist is serialised so the copy that
     * reaches disk is never older than one already written.
     */
    private fun record(
        saved: (Map<String, String>) -> Map<String, String>,
        meta: (Map<String, SavedSongMetadata>) -> Map<String, SavedSongMetadata>,
    ) {
        val savedMap = _saved.updateAndGet(saved)
        val metaMap = _savedMetadata.updateAndGet(meta)
        synchronized(recordLock) {
            prefs.putString(KEY_SAVED, json.encodeToString(serializer, savedMap))
            prefs.putString(KEY_SAVED_METADATA, json.encodeToString(metadataSerializer, metaMap))
        }
    }

    private val recordLock = Any()

    /** Returns all downloaded songs whose files still exist on disk. */
    suspend fun getDownloadedSongs(): List<Song> = withContext(Dispatchers.IO) {
        val metaMap = _savedMetadata.value
        val result = mutableListOf<Song>()
        val seenPaths = mutableSetOf<String>()

        for ((_, meta) in metaMap) {
            // A downloaded music video and the catalogue track it resolved to
            // are deliberately recorded as two ids for one file. Verify and
            // materialise that file once, not once per alias.
            if (!seenPaths.add(meta.uri)) continue
            if (DownloadStore.exists(meta.uri)) {
                val fileModifiedMillis = File(meta.uri).lastModified().takeIf { it > 0 }
                val (dateAddedSeconds, dateModifiedSeconds) = resolvedDownloadDates(
                    persistedAddedSeconds = meta.dateAddedSeconds,
                    fileModifiedMillis = fileModifiedMillis,
                )
                result.add(
                    Song(
                        videoId = meta.videoId,
                        title = meta.title,
                        artist = meta.artist,
                        thumbnailUrl = meta.thumbnailUrl,
                        durationText = meta.durationText,
                        albumName = meta.albumName,
                        localUri = meta.uri,
                        downloadFormat = meta.downloadFormat,
                        localDateAddedSeconds = dateAddedSeconds,
                        localDateModifiedSeconds = dateModifiedSeconds,
                    )
                )
            } else {
                // Clear every id for the shared file. Leaving its second alias
                // behind would make a later menu claim the missing download
                // still exists.
                metaMap.filterValues { it.uri == meta.uri }.keys.forEach(::forget)
            }
        }
        result
    }

    // ---- Driven by the drain ------------------------------------------------

    /**
     * The next track to fetch, or null when the queue is empty.
     *
     * Claims it as running under the same lock that removed it, so there is no
     * instant where a track is in neither the queue nor the running slot and a
     * [cancel] for it would quietly do nothing.
     */
    internal fun takeNext(): Song? = synchronized(lock) {
        val entry = pending.entries.firstOrNull() ?: return null
        pending.remove(entry.key)
        running[entry.key] = null
        entry.value
    }

    /** Attach the job fetching [videoId], unless it has been cancelled meanwhile. */
    internal fun onRunning(videoId: String, job: Job) {
        val cancelled = synchronized(lock) {
            if (videoId !in running) return@synchronized true
            running[videoId] = job
            false
        }
        if (cancelled) job.cancel()
    }

    /** [videoId] is finished, one way or another, and no longer holds a worker. */
    internal fun onIdle(videoId: String) {
        synchronized(lock) { running.remove(videoId) }
    }

    /** Whether anything is still queued or in flight — see [drainQueue]'s workers. */
    internal fun busy(): Boolean = synchronized(lock) { pending.isNotEmpty() || running.isNotEmpty() }

    /**
     * The whole drain going away at once — every claim in [running] is void,
     * and leaving one behind would have [enqueue] refuse that track forever as
     * already in flight. Only the shutdown path calls it; a worker finishing
     * one track reports [onIdle].
     */
    internal fun onStopped() {
        synchronized(lock) { running.clear() }
    }

    /**
     * Fetch one track, start to finish.
     *
     * Two halves, and the split is what lets a queue go at any speed: [prepare]
     * decides where the bytes come from and [transfer] moves them. Everything
     * that can go wrong past the point of reserving a destination has to
     * unreserve it — a cancelled or failed download must not leave a partial
     * file behind pretending to be a whole one, which is what
     * [DownloadStore.Pending] exists to make hard to get wrong.
     *
     * Pinned to [Dispatchers.IO] here rather than trusted to arrive on it.
     * Resolving a stream blocks on HTTP, and the drain's own scope is not an IO
     * pool — inheriting that would put every network call in the resolve on the
     * drain's threads, where a thrown error lands in the same per-client
     * `runCatching` that exists to tolerate one client being turned away, so
     * every client appears to be refused and the whole thing reads as a network
     * outage.
     *
     * Several of these run at once — see [drainQueue]. Nothing in here is
     * shared between them but the two state flows, and both are written through
     * atomic updates for exactly that reason.
     */
    internal suspend fun run(song: Song) = withContext(Dispatchers.IO) {
        val id = song.videoId
        // Set before the lookup, not after it. Resolving where the track's
        // stream comes from is the long part of a download, and leaving the row
        // on "Queued" for all of it reads as a queue that has stopped rather
        // than one that is working.
        _active.update { it + (id to DownloadState.Running(0f)) }
        DownloadSession.running(id, 0f)

        try {
            val plan = prepare(song)
            // The manager is showing the row that was tapped, which for a
            // substituted track is the wrong title for the file actually being
            // written. Corrected here rather than left to disagree with the
            // Downloads page afterwards.
            DownloadSession.retitle(id, plan.track)
            transfer(song, plan)
        } catch (e: CancellationException) {
            clear(id)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed for $id: ${e.message}", e)
            fail(id, e.friendly())
        }
    }

    /**
     * Everything that has to be known before a byte can be asked for, and
     * nothing that touches the destination.
     *
     * Split out of [run] so it *can* be done ahead of time — kept apart anyway,
     * because the split is what makes the expensive half separately measurable
     * and separately cancellable.
     *
     * Nothing here writes to [_active] or to [DownloadSession]. It may be
     * running for a track that is still queued — or for one that gets cancelled
     * before its turn — and a preparation is not a download.
     */
    internal suspend fun prepare(song: Song): Prepared = withContext(Dispatchers.IO) {
        // Downloads preserve the exact item the listener picked. Catalogue
        // matching is a manual playback action and must not silently change a
        // download or its filename.
        val track = song
        // Read once, here, for the whole of this track. The route below and
        // the re-resolve inside [Downloader.fetch] have to agree on which rung
        // they are fetching, and re-reading the setting per call would let a
        // change made mid-download splice two renditions into one file.
        val quality = AppSettings.downloadQuality.value

        // Asked before the resolve rather than after it. A file already sitting
        // in the downloads folder under a lossless extension is the answer to
        // the whole question, and spending the resolve's round trips to arrive
        // at a name we could have guessed is the difference between re-running
        // a 300-track queue in seconds and re-running it in hours. Only the
        // extensions that can only be lossless are worth guessing at: an
        // `.m4a` may be this build's ALAC or its AAC, and adopting the wrong
        // one would quietly answer a request for lossless with a transcode.
        if (quality.keepsLossless) {
            LOSSLESS_EXTENSIONS.firstNotNullOfOrNull { extension ->
                DownloadStore.existing(DownloadStore.fileNameFor(track, extension))
            }?.let { path ->
                return@withContext Prepared(song.videoId, track, route = null, alreadyAt = path)
            }
        }

        val route = routeFor(track, quality)
        Log.d(TAG, "downloading ${song.videoId} as .${route.extension} (${route.describe}, ${quality.label})")
        Prepared(song.videoId, track, route = route, alreadyAt = null)
    }

    /**
     * What [prepare] worked out, ready for a transfer to be run against it.
     *
     * [videoId] rides along so a look-ahead can be checked against the track
     * actually taken off the queue: the two diverge whenever something is
     * cancelled while its route is being resolved, and a plan applied to the
     * wrong track would write one song's bytes under another's name.
     */
    internal class Prepared(
        val videoId: String,
        /** The catalogue track behind the row, which may not be the row. */
        val track: Song,
        /** Null when [alreadyAt] answered the question instead. */
        val route: Route?,
        /** A file already in the downloads folder that is this download, if there is one. */
        val alreadyAt: String?,
    )

    /**
     * Fetch the bytes [plan] points at and publish them.
     *
     * Owns the destination from end to end: every exit out of here either
     * commits or aborts, so a caller is free to call it a second time with a
     * freshly resolved plan without the first attempt leaving anything behind.
     */
    private suspend fun transfer(song: Song, plan: Prepared) {
        val id = song.videoId
        val track = plan.track

        // Already there from a previous run the record lost track of — adopt it
        // rather than writing a second copy beside it.
        plan.alreadyAt?.let { path ->
            remember(song, track, path)
            DownloadSession.done(id)
            clear(id)
            return
        }
        val route = plan.route ?: error("Nothing to download")

        var pending: DownloadStore.Pending? = null
        var lyrics: Deferred<LyricsTag.Embeddable?>? = null
        var artwork: Deferred<MediaTagger.Artwork?>? = null
        try {
            coroutineScope {
                // Started before the transfer rather than after it, so the lyric
                // sources are being raced while the bytes are already moving.
                // Done after the commit instead, every download would pay the
                // slowest of them in dead time — and it is a *suspending* wait,
                // so it would sit in the one stretch of this function that has
                // no way back: past the commit, [pending] is null and a
                // cancellation there would abandon a finished file that nothing
                // has recorded yet. Awaited below while there is still a
                // pending destination to abort.
                //
                // [LyricsTag.forTrack] is contracted not to throw for anything
                // but cancellation, and that contract is load-bearing here:
                // this is a plain child of the scope, so a failure inside it
                // would cancel the download it was only meant to decorate.
                if (MediaTagger.carriesTags(route.extension)) {
                    lyrics = async { LyricsTag.forTrack(track) }
                    artwork = async { MediaTagger.artworkFor(track) }
                }

                val name = DownloadStore.fileNameFor(track, route.extension)
                val alreadyThere = DownloadStore.existing(name)
                if (alreadyThere != null) {
                    Log.d(TAG, "$name is already downloaded; adopting it")
                    remember(song, track, alreadyThere)
                    DownloadSession.done(id)
                    clear(id)
                    return@coroutineScope
                }

                val destination = DownloadStore.begin(name, route.mimeType)
                pending = destination
                destination.openStream().use { sink ->
                    route.write(sink) { written, total ->
                        val fraction = written.toFloat() / total
                        _active.update { it + (id to DownloadState.Running(fraction)) }
                        DownloadSession.running(id, fraction)
                    }
                }
                val words = lyrics?.await()
                val cover = artwork?.await()
                // Publish only after metadata is part of the file. This keeps
                // concurrent album workers from exposing untagged tracks.
                MediaTagger.embed(destination.tagPath, track, route.extension, words, cover)
                val artworkPath = MediaTagger.persistArtwork(track.thumbnailUrl, cover)
                val savedPath = destination.commit()
                pending = null
                remember(song, track, savedPath, route.downloadFormat, artworkPath)
                DownloadSession.done(id)
                clear(id)
                Log.d(TAG, "saved $name")
            }
        } catch (e: Throwable) {
            pending?.abort()
            throw e
        } finally {
            // Every exit needs this, not just the failing ones: the adopt-it
            // path above returns with the lookup still in flight, and
            // [coroutineScope] does not return while a child of it is running —
            // so an unwaited job would hold the whole queue up for the length
            // of a lyrics search per already-downloaded track.
            lyrics?.cancel()
            artwork?.cancel()
        }
    }

    /**
     * One resolved download: what to call the file, what to tell the store it
     * is, and how to fill it.
     *
     * Upstream this carried a second branch — configured sources, and the
     * offline DASH/HLS packages they can arrive as. The desktop has no source
     * layer, so the class is down to the YouTube route's four answers.
     */
    internal class Route(
        val extension: String,
        val mimeType: String,
        /** For the log line, so a download's provenance is on the record. */
        val describe: String,
        /** Short rendition badge shown only in BitChord's Downloads list. */
        val downloadFormat: String? = null,
        val write: suspend (OutputStream, (written: Long, total: Long) -> Unit) -> Unit,
    )

    /**
     * Where this download's bytes are coming from: YouTube, resolved under the
     * ceiling the setting pinned for this track.
     *
     * @param quality read once by the caller and passed down, so that a setting
     *   changed while this track is in the queue applies to the next one rather
     *   than to the middle of this one. [Downloader.fetch] resolves again after
     *   a mid-download refusal and has to ask for the same rung it started on.
     */
    private suspend fun routeFor(track: Song, quality: DownloadQuality): Route {
        val stream = StreamResolver.resolveForDownload(track.videoId, quality.maxKbps)
        return Route(
            extension = stream.downloadExtension,
            mimeType = stream.downloadMimeType,
            describe = "${stream.kbps}kbps ${stream.mimeType}",
            downloadFormat = youtubeDownloadBadge(stream.downloadExtension, stream.kbps),
            write = { sink, onProgress ->
                Downloader.fetch(track.videoId, stream, quality.maxKbps, requireM4a = false, sink, onProgress)
            },
        )
    }

    /** Back to "not downloaded" — used for success, where [saved] takes over, and for cancellation. */
    private fun clear(videoId: String) {
        _active.update { it - videoId }
    }

    private fun fail(videoId: String, reason: String) {
        _active.update { it + (videoId to DownloadState.Failed(reason)) }
        DownloadSession.failed(videoId, reason)
    }

    /**
     * A failure a user can read. The message on an [error] raised in this
     * package is already written for them; anything else is a network fault
     * with a class name for a message.
     */
    private fun Exception.friendly(): String = when {
        (this is IllegalStateException || this is IllegalArgumentException) &&
            !message.isNullOrBlank() -> message!!
        else -> "Download failed — check your connection"
    }

    /**
     * The extensions a file in the downloads folder can carry that say, on
     * their own, that a lossless request has already been answered.
     *
     * `m4a` is deliberately absent even though [DownloadStore.storable] files
     * ALAC as one: an `.m4a` in this folder is just as likely to be the AAC a
     * download at the High rung wrote, and there is nothing in the name to
     * separate them. Guessing wrong there would answer a request for lossless
     * with a transcode and never fetch the real thing.
     */
    private val LOSSLESS_EXTENSIONS = listOf("flac", "wav")

    /** Dropped when the menu reopens; a failure is worth showing once. */
    fun dismissFailure(videoId: String) {
        if (_active.value[videoId] is DownloadState.Failed) clear(videoId)
    }

    // ---- The drain ----------------------------------------------------------
    //
    // DownloadService's worker loop, without the Service. Four at once, because
    // the resolve is the long half of a download and overlap is the whole
    // point; the idle grace is the service's own answer to a batch landing over
    // several enqueues, the first of which started the drain while the rest
    // were still being asked for.

    /** How many downloads may be in flight at once. */
    internal const val WORKERS = 4

    /** How long a worker keeps looking at an empty queue before it accepts the queue is empty. */
    private const val IDLE_GRACE_MS = 2_000L

    private const val IDLE_POLL_MS = 100L

    private val drainScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var drain: Job? = null

    private fun ensureDraining() {
        if (drain?.isActive == true) return
        drain = drainScope.launch { drainQueue() }
    }

    private suspend fun drainQueue() = coroutineScope {
        repeat(WORKERS) { launch { work() } }
        onStopped()
    }

    private suspend fun CoroutineScope.work() {
        var idleFor = 0L
        while (true) {
            val song = takeNext()
            if (song == null) {
                // Nothing to take, but something may still be arriving. Only a
                // queue that stays empty, with nothing else in flight, is
                // finished.
                if (idleFor >= IDLE_GRACE_MS && !busy()) return
                delay(IDLE_POLL_MS)
                idleFor += IDLE_POLL_MS
                continue
            }
            idleFor = 0L

            // Its own job, so one track can be cancelled out from under the
            // loop without taking the rest of the queue with it.
            val job = launch { run(song) }
            onRunning(song.videoId, job)
            job.join()
            onIdle(song.videoId)
        }
    }
}

@kotlinx.serialization.Serializable
internal data class SavedSongMetadata(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    /**
     * What release this track is off, when the row it was downloaded from knew.
     *
     * Added after the fact and defaulted, so a record written before it existed
     * still decodes.
     */
    val albumName: String? = null,
    val uri: String,
    val downloadFormat: String? = null,
    /** Stable creation time, kept in our own record rather than any index's. */
    val dateAddedSeconds: Long? = null,
)

/**
 * Dates for a downloaded file-backed row.
 *
 * Old metadata has no persisted creation time, so the file timestamp is the
 * only honest migration value. From then on the persisted value stays fixed,
 * while modified time continues to follow the file on disk.
 */
internal fun resolvedDownloadDates(
    persistedAddedSeconds: Long?,
    fileModifiedMillis: Long?,
): Pair<Long?, Long?> {
    val modifiedSeconds = fileModifiedMillis?.takeIf { it > 0 }?.div(1_000)
    return (persistedAddedSeconds ?: modifiedSeconds) to modifiedSeconds
}

/**
 * YouTube does not expose a named quality tier once the resolved file has been
 * saved, so retain the concrete codec/container choice and bitrate alongside
 * the download. This is display metadata only: the bytes are still the exact
 * WebM/Opus or MP4/AAC rendition returned by YouTube.
 */
internal fun youtubeDownloadBadge(extension: String, kbps: Int): String? {
    if (kbps <= 0) return null
    val codec = if (extension.equals("m4a", ignoreCase = true)) "AAC" else "OPUS"
    return "$codec · $kbps kbps"
}
