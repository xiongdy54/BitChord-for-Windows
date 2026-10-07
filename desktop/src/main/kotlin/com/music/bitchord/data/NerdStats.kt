package com.music.bitchord.data

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * What the player is being fed, for "stats for nerds" — the desktop counterpart
 * of the Android class of the same name.
 *
 * Two kinds of figure meet here, and they are kept apart for the same reason
 * upstream keeps them apart: what the *resolver* recorded about the stream it
 * picked (below), and what the *decoder* reports about the bytes actually
 * arriving (a [Snapshot] in [current]). The first is written by
 * `StreamResolver` when it settles on a stream; the second arrives from
 * libvlc's own track description once the input has opened. Nothing here is
 * inferred from the settings, and a figure nobody has reported stays null
 * rather than being guessed at.
 *
 * Upstream also carries a `claimed` StreamFormat per track — what a third-party
 * module said it was about to send — and the `downgraded` comparison against
 * the measurement. Desktop has no module sources yet: the one source is
 * YouTube's own resolver, whose declared format is what actually plays, so
 * there is nothing to hold a promise against a truth and both fields stay out.
 */
object NerdStats {

    /**
     * What the decoder is actually being fed, as measured at the input.
     *
     * The labels derived here are decided on the measurements alone —
     * "Lossless" means the decoder says so, not that a source promised it.
     */
    class Snapshot(
        /**
         * The codec name libvlc reports for the audio track ("opus", "flac",
         * "mp4a", "araw"...). Not a MIME type, though it ends in one of the
         * suffixes [isLosslessMime] reads — the codec is the honest word for
         * what crossed the decoder, and libvlc does not hand over MIME types.
         */
        val mimeType: String?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val channels: Int?,
        /** libvlc does not state a bit depth; nothing on desktop fills this. */
        val bitDepth: Int? = null,
        /** Authoritative source name that supplied this stream. */
        val sourceName: String? = null,
    ) {
        /** The decoder is genuinely being fed a bit-exact codec. */
        val isLossless: Boolean
            get() = isLosslessMime(mimeType)

        /** Dolby Atmos decoded as E-AC-3 JOC; deliberately distinct from lossless. */
        val isDolbyAtmos: Boolean
            get() = isDolbyAtmosMime(mimeType)

        /**
         * Better than CD quality — past 16-bit or past 48kHz, not merely
         * lossless. With no bit depth on this platform the depth half of the
         * test is dead weight: a rate over 48kHz is what Hi-Res can mean here,
         * and a 16/192 stream that another platform would name is simply
         * called "Lossless" rather than guessed into a claim.
         */
        val isHiRes: Boolean
            get() = isLossless && ((bitDepth ?: 0) > 16 || (sampleRateHz ?: 0) > 48_000)

        /**
         * Lossy, but at the top of what lossy gets — decided on the measured
         * bitrate, the same rule upstream draws, so a 256kbps stream is a
         * 256kbps stream wherever it came from.
         */
        val isHiQuality: Boolean
            get() = !isLossless && (bitrateKbps ?: 0) >= HI_QUALITY_KBPS

        /**
         * Medium lossy quality (e.g. YouTube Opus ~160kbps, AAC 128-192kbps).
         */
        val isMediumQuality: Boolean
            get() = !isLossless && (bitrateKbps ?: 0) in (LOW_QUALITY_MAX_KBPS + 1) until HI_QUALITY_KBPS

        /**
         * Low quality lossy (e.g. YouTube Opus 50-70kbps).
         */
        val isLowQuality: Boolean
            get() = !isLossless && (bitrateKbps ?: 0) in 1..LOW_QUALITY_MAX_KBPS
    }

    /**
     * What the renderer's input looks like when the bytes behind it are
     * bit-exact. "raw" is libvlc's name for PCM, as "raw" is Media3's.
     */
    private val LOSSLESS_CODEC_SUFFIXES = listOf("flac", "alac", "raw")

    fun isLosslessMime(mimeType: String?): Boolean =
        mimeType != null && LOSSLESS_CODEC_SUFFIXES.any { mimeType.endsWith(it) }

    fun isDolbyAtmosMime(mimeType: String?): Boolean =
        mimeType != null && (mimeType.endsWith("eac3-joc") || mimeType.endsWith("eac3"))

    /**
     * The upper bound in kbps for a low-bitrate / data-saver lossy stream.
     */
    const val LOW_QUALITY_MAX_KBPS = 80

    /**
     * The bitrate a lossy stream has to reach to be worth calling out.
     *
     * At 256 an Apple-style AAC counts and YouTube's Opus, which tops out
     * around 160, does not — which is the distinction the label exists to
     * draw.
     */
    const val HI_QUALITY_KBPS = 256

    fun codecLabel(mimeType: String?): String? {
        if (mimeType == null) return null
        return when {
            mimeType.endsWith("flac", ignoreCase = true) -> "FLAC"
            mimeType.endsWith("opus", ignoreCase = true) -> "Opus"
            mimeType.endsWith("mp4a-latm", ignoreCase = true) || mimeType.endsWith("aac", ignoreCase = true) -> "AAC"
            mimeType.endsWith("mpeg", ignoreCase = true) || mimeType.endsWith("mp3", ignoreCase = true) -> "MP3"
            mimeType.endsWith("alac", ignoreCase = true) -> "ALAC"
            mimeType.endsWith("raw", ignoreCase = true) -> "PCM"
            mimeType.endsWith("vorbis", ignoreCase = true) -> "Vorbis"
            mimeType.endsWith("eac3-joc", ignoreCase = true) -> "E-AC-3 JOC"
            mimeType.endsWith("eac3", ignoreCase = true) -> "E-AC-3"
            mimeType.contains("/") -> mimeType.substringAfterLast("/").uppercase()
            else -> mimeType.uppercase()
        }
    }

    /**
     * The decoder's verdict for the row now playing.
     *
     * Nulled across a track change ([clear]) so a badge can never describe the
     * previous track's figures to the current one — the reason upstream gives
     * at length for the same rule.
     */
    val current = MutableStateFlow<Snapshot?>(null)

    /** Which track [current] describes, so a late measurement cannot land on the wrong song. */
    @Volatile
    private var currentVideoId: String? = null

    /**
     * The decoder's report for [videoId], which must still be the row now
     * playing — a measurement that lands after the needle moved is discarded
     * the way a superseded resolve is.
     */
    fun onMeasured(videoId: String?, snapshot: Snapshot) {
        currentVideoId = videoId
        current.value = snapshot
    }

    /** The track [current] describes, for the UI's "is this mine" check. */
    fun currentVideoId(): String? = currentVideoId

    /** The old track's figures leave with the old track, not when the new ones arrive. */
    fun clear() {
        currentVideoId = null
        current.value = null
    }

    private val picked = ConcurrentHashMap<String, Int>()

    /** As [picked], for the MIME type the resolver declared the stream to be. */
    private val mimes = ConcurrentHashMap<String, String>()

    /** Authoritative source name that supplied the stream, keyed by media/track id. */
    private val sources = ConcurrentHashMap<String, String>()

    fun onStreamPicked(videoId: String, kbps: Int, source: String? = null, mimeType: String? = null) {
        if (kbps > 0) {
            // Enough for the queue in hand; this is a lookup, not a store.
            if (picked.size >= MAX_REMEMBERED) picked.clear()
            picked[videoId] = kbps
        }
        if (!mimeType.isNullOrBlank()) {
            if (mimes.size >= MAX_REMEMBERED) mimes.clear()
            mimes[videoId] = mimeType
        }
        if (!source.isNullOrBlank()) {
            if (sources.size >= MAX_REMEMBERED) sources.clear()
            sources[videoId] = source
        }
    }

    /** The bitrate the last resolve settled on for [videoId], if it is still remembered. */
    fun pickedKbps(videoId: String): Int? = picked[videoId]

    /** The MIME type the resolver declared for [videoId], if it is still remembered. */
    fun pickedMimeType(videoId: String): String? = mimes[videoId]

    fun pickedSource(videoId: String): String? = sources[videoId]

    private const val MAX_REMEMBERED = 64
}
