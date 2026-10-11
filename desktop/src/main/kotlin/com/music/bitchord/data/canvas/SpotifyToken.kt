package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * The bearer token behind Spotify's own web player, minted from the
 * listener's session cookie ([AppSettings.spotifySpdcToken]) rather than an
 * app credential — there is no public API for a track's Canvas, so this walks
 * the same door the web player itself uses to fetch one.
 *
 * Desktop form (slice 21): the sp_dc cookie is a setting the user holds, and
 * the token endpoint is asked directly with it. Upstream stopped trusting
 * this door — its harvest loads the whole web player in an offscreen WebView
 * and reads the token the page mints for itself — so this mint is the
 * best-effort half: when the endpoint answers, Canvas works through the REST
 * fallback (bearer is all it needs; the canvaz-cache GraphQL path wants a
 * client token this build does not mint and [clientToken] answers null); when
 * it is gated or the cookie is stale, one log line and null, which is the
 * shape [SpotifyCanvas] already treats as "ask the next provider". The JCEF
 * harvest upstream's path implies is its own slice if this door closes.
 */
object SpotifyToken {

    private const val TAG = "SpotifyToken"

    /** The web player's own token door, asked with the session cookie. */
    internal var tokenEndpoint: String =
        "https://open.spotify.com/api/token?reason=transport&productType=web_player"

    /** Tokens live about an hour; this build re-mints shortly before the hour is out. */
    private const val EXPIRY_MARGIN_MS = 30_000L

    /** The endpoint's own figure when it declines to state one. */
    private const val DEFAULT_LIFETIME_MS = 3_600_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var cachedAccessToken: String? = null
    @Volatile private var accessTokenExpiresAtMs = 0L

    /**
     * The current bearer, or null when there is no cookie to mint one from or
     * the mint failed. Cached until shortly before it expires so a skip
     * through a queue doesn't pay for this per track.
     */
    fun accessToken(): String? {
        val cookie = AppSettings.spotifySpdcToken.value.trim()
        if (cookie.isBlank()) return null
        val now = System.currentTimeMillis()
        cachedAccessToken?.let { if (now < accessTokenExpiresAtMs - EXPIRY_MARGIN_MS) return it }
        return synchronized(this) {
            val stillNow = System.currentTimeMillis()
            cachedAccessToken?.let { if (stillNow < accessTokenExpiresAtMs - EXPIRY_MARGIN_MS) return it }
            mint(cookie)?.also { harvested ->
                cachedAccessToken = harvested.token
                accessTokenExpiresAtMs = harvested.expiresAt
            }?.token
        }
    }

    /**
     * The per-install client token Spotify's canvaz-cache endpoint asks for
     * alongside the bearer. This build mints no client token — the endpoint
     * that produces one is part of the web-player harvest upstream runs — so
     * the answer is always null and [SpotifyCanvas] takes its REST fallback,
     * which treats the header as optional.
     */
    fun clientToken(): String? = null

    private fun mint(cookie: String): HarvestedToken? = runCatching {
        val request = Request.Builder()
            .url(tokenEndpoint)
            .header("Cookie", "sp_dc=$cookie")
            .build()
        val body = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "token mint answered HTTP ${response.code}")
                return null
            }
            response.body?.string()
        } ?: return null
        val obj = json.parseToJsonElement(body) as? JsonObject ?: return null
        val token = obj["accessToken"]?.jsonPrimitive?.contentOrNull ?: return null
        val expiresAt = obj["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.longOrNull
            ?: (System.currentTimeMillis() + DEFAULT_LIFETIME_MS)
        Log.d(TAG, "minted a Spotify bearer, ${expiresAt - System.currentTimeMillis()}ms of life")
        HarvestedToken(token, expiresAt)
    }.getOrNull()

    private data class HarvestedToken(val token: String, val expiresAt: Long)
}
