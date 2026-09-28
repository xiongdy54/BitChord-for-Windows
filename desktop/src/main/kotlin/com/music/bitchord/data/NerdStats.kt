package com.music.bitchord.data

import java.util.concurrent.ConcurrentHashMap

/**
 * What the resolver decided, kept for a "stats for nerds" surface to read
 * later — the desktop counterpart of the Android class of the same name,
 * holding only the part the ported resolve path writes today.
 */
object NerdStats {

    private val picked = ConcurrentHashMap<String, Int>()

    /** Authoritative source name that supplied the stream, keyed by media/track id. */
    private val sources = ConcurrentHashMap<String, String>()

    fun onStreamPicked(videoId: String, kbps: Int, source: String? = null) {
        if (kbps > 0) {
            // Enough for the queue in hand; this is a lookup, not a store.
            if (picked.size >= MAX_REMEMBERED) picked.clear()
            picked[videoId] = kbps
        }
        if (!source.isNullOrBlank()) {
            if (sources.size >= MAX_REMEMBERED) sources.clear()
            sources[videoId] = source
        }
    }

    /** The bitrate the last resolve settled on for [videoId], if it is still remembered. */
    fun pickedKbps(videoId: String): Int? = picked[videoId]

    fun pickedSource(videoId: String): String? = sources[videoId]

    private const val MAX_REMEMBERED = 64
}
