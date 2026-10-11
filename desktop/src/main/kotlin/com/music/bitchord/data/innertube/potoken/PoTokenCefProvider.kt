package com.music.bitchord.data.innertube.potoken

import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The session cache in front of [PoTokenCefSession] — the desktop's shape of the
 * Android PoTokenGenerator, with the same two-token shape:
 *
 *  * a **streaming pot**, minted once per visitor session and attached to the
 *    player *request*;
 *  * a **player pot**, minted per videoId and attached to the *streamingData*
 *    URLs.
 *
 * The streaming pot must be the first token a fresh session mints (the BotGuard
 * minter binds it); every visitor change, expiry, crash or timeout rebuilds the
 * hidden browser from scratch. A generation failure gets exactly one rebuild
 * before giving up — the same "recreate and retry once" the Android generator
 * runs when the WebView's content was lost.
 */
internal object PoTokenCefProvider {

    private const val TAG = "PoTokenCef"

    private val lock = Mutex()
    private var session: PoTokenCefSession? = null
    private var sessionId: String? = null
    private var streamingPot: String? = null

    /** `(streaming pot, player pot)` for [videoId] under the [visitorData] session. */
    suspend fun poToken(videoId: String, visitorData: String): Pair<String, String> = lock.withLock {
        try {
            val (generator, streaming) = ensureGenerator(visitorData)
            streaming to generator.generatePoToken(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TrackLog.e(TAG, "poToken generation failed; recreating the browser (${e.message})")
            discardGenerator()
            val (fresh, freshStreaming) = ensureGenerator(visitorData)
            // The second try has a freshly minted browser behind it; whatever
            // fails now is a failure worth surfacing, not retrying.
            freshStreaming to fresh.generatePoToken(videoId)
        }
    }

    /**
     * Drops the hidden browser — for app shutdown and "start over" paths.
     * Best effort without the lock: shutdown cannot wait on an in-flight mint.
     */
    fun close() {
        discardGenerator()
    }

    private suspend fun ensureGenerator(visitorData: String): Pair<PoTokenCefSession, String> {
        val current = session
        val currentPot = streamingPot
        if (current != null && currentPot != null && !current.isDead && !current.isExpired &&
            sessionId == visitorData
        ) {
            return current to currentPot
        }
        discardGenerator()
        val fresh = PoTokenCefSession.new()
        // The streaming poToken is generated exactly once, before any player pot.
        val pot = fresh.generatePoToken(visitorData)
        session = fresh
        sessionId = visitorData
        streamingPot = pot
        TrackLog.d(TAG, "streaming poToken generated for a fresh browser session")
        return fresh to pot
    }

    private fun discardGenerator() {
        session?.let { runCatching { it.close() } }
        session = null
        sessionId = null
        streamingPot = null
    }
}
