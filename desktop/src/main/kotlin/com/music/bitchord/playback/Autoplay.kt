package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry

/** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. `Autoplay.kt:15`. */
const val MAX_QUEUED_AUTOPLAY = 10

/**
 * The bounded retry for an empty radio answer, lifted from `PlaybackService.kt:7725-7728`
 * with the service's constants: one delayed re-request while the current track is still
 * the queue's end, and never more — a radio with no usable unique songs must not turn
 * into a permanent background polling loop.
 */
const val AUTOPLAY_EMPTY_REFRESH_DELAY_MS = 2_000L
const val MAX_AUTOPLAY_EMPTY_REFRESHES = 1

/**
 * Whether AutoPlay should forget its last seed and try the current track again.
 *
 * A completed load normally leaves its seed behind to de-duplicate the several
 * player callbacks caused by one queue edit. If that edit leaves the current
 * track with nothing after it, however, the seed is no longer useful: keeping
 * it would make an enabled AutoPlay queue stay empty forever.
 *
 * `Autoplay.kt:66`, verbatim.
 */
fun autoplayQueueNeedsRefresh(
    enabled: Boolean,
    repeatAll: Boolean,
    currentIndex: Int,
    itemCount: Int,
    loadInProgress: Boolean,
): Boolean = enabled &&
    !repeatAll &&
    !loadInProgress &&
    currentIndex >= 0 &&
    currentIndex == itemCount - 1

/**
 * Finds the YouTube id that should seed AutoPlay for a song.
 *
 * Upstream also matches module and local tracks — ids carrying a source
 * registry key — onto YouTube before the radio request. Desktop has no
 * source registry yet: every id that reaches this function is already a
 * YouTube id, so the match step is a seam note rather than code. When local
 * music lands, this is where its tracks get looked up.
 */
suspend fun youtubeSeedFor(song: Song): String? = song.videoId

/**
 * Loads, de-duplicates and resolves one AutoPlay batch.
 *
 * `Autoplay.kt:99`, verbatim: the radio watch queue seeded on the song,
 * `QueueBuilder.extend` against what the queue already holds, each survivor
 * re-tagged with the seed's own playback source and stamped into the
 * [QueueTier.AUTOPLAY] tier.
 *
 * The radio request rides a parameter with the production call as its default —
 * the same shape [com.music.bitchord.desktop.playback.PlayerController.resolveUrl]
 * uses — so a unit test injects its answer instead of wiring the network.
 */
suspend fun loadAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    limit: Int = MAX_QUEUED_AUTOPLAY,
    radio: suspend (String) -> Result<List<Song>>,
): Result<List<Song>> {
    val seed = youtubeSeedFor(seedSong) ?: return Result.success(emptyList())
    val related = radio(seed).getOrElse { return Result.failure(it) }
    val extra = QueueBuilder.extend(existing, related, limit)
    if (extra.isEmpty()) return Result.success(emptyList())

    return Result.success(extra.map {
        it.copy(
            radioName = seedSong.radioName,
            playbackSource = seedSong.playbackSource,
            playbackSourceType = seedSong.playbackSourceType,
            playbackSourceId = seedSong.playbackSourceId,
        ).asQueueEntry(QueueTier.AUTOPLAY)
    })
}
