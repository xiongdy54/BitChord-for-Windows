package com.music.bitchord.data.canvas

// Desktop stand-in for the Android app's SpotifyToken (app
// data/canvas/SpotifyToken.kt), which mints an access token from the
// listener's own `sp_dc` session cookie through an offscreen WebView harvest.

/**
 * The listener-side credential [SpotifyCanvas] needs, or the absence of one.
 *
 * Upstream answers [accessToken] out of a session cookie the app harvests in
 * an offscreen WebView (Settings > Spotify Canvas setup); the desktop port's
 * equivalent runs through the embedded browser (JCEF) and is its own follow-up
 * slice. Until it exists there is no credential here, so both reads answer
 * null — which is exactly the shape upstream already handles: with no token
 * [SpotifyCanvas] logs one line and returns without a request, leaving the
 * other three providers to answer. Nothing else in the chain changes.
 *
 * Kept as an object with the upstream names so the harvest slice can fill
 * these two functions in without touching [SpotifyCanvas] at all.
 */
object SpotifyToken {

    /**
     * A bearer token minted from the listener's `sp_dc` cookie, valid about an
     * hour — null with no cookie set, which is the permanent answer on this
     * build until the JCEF harvest lands.
     */
    fun accessToken(): String? = null

    /**
     * The per-install client token Spotify's own endpoints ask for alongside
     * the bearer — null while [accessToken] is, for the same reason.
     */
    fun clientToken(): String? = null
}
