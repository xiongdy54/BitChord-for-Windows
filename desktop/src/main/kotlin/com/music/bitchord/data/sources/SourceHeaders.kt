package com.music.bitchord.data.sources

/**
 * Where a [SourceStream]'s fetch headers survive the desktop resolve seam.
 *
 * Upstream hands the player a `DataSpec` that carries the stream's headers
 * alongside its URL. The desktop seam between resolver and engine is a bare
 * URL string — `PlayerController.resolveUrl` — so the headers a module or
 * addon demanded (a token, a referer, a cookie) need somewhere to wait for
 * the engine to ask. `PlayerController.mediaHeaders` consults this before
 * falling through to its URL-shape guesses, which is the same order upstream
 * implies: what the source said about its own stream outranks what the host
 * can be guessed to want.
 *
 * Keyed by URL, LRU-capped. A resolve produces a fresh signed URL each time,
 * so one entry per resolve is the steady state; the cap only exists so a long
 * session of streams nobody played cannot grow this without bound.
 */
object SourceHeaders {

    private const val CAP = 64

    private val remembered = object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>): Boolean =
            size > CAP
    }

    fun remember(url: String, headers: Map<String, String>) {
        if (headers.isEmpty()) return
        synchronized(this) { remembered[url] = headers }
    }

    fun headersFor(url: String): Map<String, String>? =
        synchronized(this) { remembered[url] }
}
