package com.music.bitchord.auth

import com.music.bitchord.data.DebugLog as Log
import org.cef.callback.CefCookieVisitor
import org.cef.callback.CefCompletionCallback
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.util.Date

/** What the login browser is being opened for. */
enum class WebSessionMode {
    /** No usable session yet: sign in to Google. */
    SIGN_IN,

    /**
     * Already signed in, but on the wrong channel. Opens YouTube Music itself
     * so its own Accounts switcher can be used, and takes the session from
     * whatever page the listener ends up on.
     */
    SWITCH_CHANNEL,
}

/**
 * Value accepted by Innertube as `context.user.onBehalfOfUser`.
 * YouTube commonly exposes `DATASYNC_ID` as `account||delegated`; the second
 * half is the active identity, while plain accounts can leave it empty.
 */
internal fun normalizeDataSyncId(raw: String?): String? {
    val value = raw?.takeIf { it.isNotBlank() } ?: return null
    if (!value.contains("||")) return value
    return value.substringAfter("||").takeIf { it.isNotBlank() }
        ?: value.substringBefore("||").takeIf { it.isNotBlank() }
}

/**
 * A session lifted out of the login browser: the cookie, plus who the page
 * being looked at says it is.
 *
 * The identity fields come from the live page's `ytcfg` rather than from a
 * later server-side fetch of the shell, and that is the entire point. Which
 * channel YouTube Music serves by default is not a question this app gets to
 * answer, but which channel the page in front of the listener is *currently*
 * showing is not a question at all — it is written down in the page. Reading
 * it there is what lets "switch to the channel I want, then save" work.
 *
 * Individual identity fields are nullable because a personal viewer need not
 * have a channel. The capture itself is accepted only from a page whose config
 * says it is signed in, and the API verifies it before it is persisted.
 */
data class CapturedSession(
    val cookie: String,
    /** `DELEGATED_SESSION_ID` — set only while a brand channel is selected. */
    val pageId: String?,
    /** Active identity used as `context.user.onBehalfOfUser`. */
    val dataSyncId: String?,
    /** `SESSION_INDEX` — which Google account in the cookie jar. */
    val authUser: String?,
    val visitorData: String?,
    val clientVersion: String?,
    /** Whether the page reported itself signed in at all. */
    val loggedIn: Boolean,
)

/**
 * The login browser's own cookie jar, which is not the app's.
 *
 * These are separate stores and the difference is invisible until it bites:
 * signing out of BitChord forgets the cookie the app makes requests with, and
 * leaves the browser's copy untouched. The next sign-in then loads
 * accounts.google.com, is recognised immediately, redirects straight through
 * to music.youtube.com and hands back a cookie for the account that was just
 * signed out of — a sign-in screen that cannot be used to sign in as anyone
 * else, and shows barely a flicker while refusing to.
 *
 * On the desktop the jar is the CEF global manager's, backed by the
 * `cef/` cache directory so it survives restarts — the same persistence the
 * Android WebView's jar has. Every call here is best effort: a browser that
 * never started has no manager, which is a reason for the next sign-in to be
 * less convenient, never a reason for signing out to crash. All four calls
 * run synchronously on the calling thread — JCEF's cookie manager blocks
 * until the native side answers — so they are always reached off the UI
 * dispatcher.
 */
object BrowserSession {

    private fun manager(): CefCookieManager? = runCatching { CefCookieManager.getGlobalManager() }
        .getOrElse {
            Log.w("BitChord", "no cookie manager: ${it.message}")
            null
        }

    /**
     * Makes the login browser use a saved account without asking Google to
     * sign any account out.
     *
     * The encrypted account registry owns independent cookie snapshots. The
     * browser, however, has one process-wide jar, so opening the channel picker
     * after selecting an older account would otherwise show whichever account
     * happened to sign in most recently. Replace the local jar with that
     * account's snapshot before loading YouTube Music. Header attributes do not
     * survive a capture (the same loss the Android `setCookie` string API has),
     * so every entry is re-issued `Secure` on its origin's host.
     */
    fun installGoogleCookies(cookieHeader: String) {
        clearGoogleCookies()
        val jar = manager() ?: return
        val entries = cookieHeader.split(';').mapNotNull { entry ->
            val name = entry.substringBefore('=').trim()
            val value = entry.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }
        if (entries.isEmpty()) return
        val now = Date()
        var installed = 0
        YOUTUBE_ORIGINS.forEach { origin ->
            val host = origin.substringAfter("://")
            entries.forEach { (name, value) ->
                // Positional arguments: CefCookie is a Java constructor, and
                // its parameter names do not survive into Kotlin.
                if (jar.setCookie(
                        origin,
                        CefCookie(name, value, host, "/", true, false, now, now, false, epoch),
                    )
                ) {
                    installed++
                }
            }
        }
        jar.flushStore(CefCompletionCallback { })
        Log.d("BitChord", "restored $installed browser cookies for the selected account")
    }

    /**
     * Forgets the Google login the login browser is holding.
     *
     * Google's cookies only, by name, rather than wiping the whole store: the
     * same jar will hold whatever other logins later slices park in it, and
     * signing out of YouTube Music is not a reason to sign out of those. There
     * is no per-domain removal in the API, so each cookie is both deleted by
     * name and overwritten with an expired one. Both the host-only and the
     * domain-wide form are covered: a cookie set on `.google.com` is not
     * removed by expiring it on the host, and which of the two a given cookie
     * used is not recorded here. Unlike the Android original, HttpOnly cookies
     * are visited too — CEF can list them, and a jar cleared except for its
     * HttpOnly half is half cleared.
     */
    fun clearGoogleCookies() {
        val jar = manager() ?: return
        var cleared = 0
        GOOGLE_ORIGINS.forEach { origin ->
            val host = origin.substringAfter("://")
            // A visit to learn the names first; the visitor runs synchronously.
            val names = mutableListOf<String>()
            jar.visitUrlCookies(
                origin,
                true,
                CefCookieVisitor { cookie, _, _, _ ->
                    names += cookie.name
                    true
                },
            )
            names.forEach { name ->
                jar.deleteCookies(origin, name)
                expired(name, host).let { (domainWide, hostOnly) ->
                    jar.setCookie(origin, hostOnly)
                    jar.setCookie(origin, domainWide)
                }
                cleared++
            }
        }
        jar.flushStore(CefCompletionCallback { })
        Log.d("BitChord", "cleared $cleared browser cookies for Google")
    }

    /**
     * Reads every cookie the jar holds for the Music origin — HttpOnly ones
     * included, since the signing secret is one of them — and flushes the
     * store on the way out: the dialog is usually closing in the next frame,
     * and a cookie jar written after that is a jar the next sign-in reads
     * instead of this one.
     *
     * @return a cookie header in the `name=value; …` form the registry stores,
     *   or null when the jar has nothing (or no jar exists).
     */
    fun readMusicCookies(): String? {
        val jar = manager() ?: return null
        val entries = mutableListOf<String>()
        jar.visitUrlCookies(
            MUSIC_ORIGIN,
            true,
            CefCookieVisitor { cookie, _, _, _ ->
                entries += "${cookie.name}=${cookie.value}"
                true
            },
        )
        if (entries.isNotEmpty()) jar.flushStore(CefCompletionCallback { })
        return entries.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /** An expired host-only and domain-wide pair for [name] on [host]. */
    private fun expired(name: String, host: String): Pair<CefCookie, CefCookie> {
        fun expiredCookie(domain: String) = CefCookie(
            name, "", domain, "/", false, false, epoch, epoch, true, epoch,
        )
        return expiredCookie(".$host") to expiredCookie(host)
    }

    private val epoch = Date(0)

    private const val MUSIC_ORIGIN = "https://music.youtube.com"

    private val GOOGLE_ORIGINS = listOf(
        "https://music.youtube.com",
        "https://www.youtube.com",
        "https://youtube.com",
        "https://accounts.google.com",
        "https://www.google.com",
        "https://google.com",
    )

    private val YOUTUBE_ORIGINS = listOf(
        "https://music.youtube.com",
        "https://www.youtube.com",
        "https://youtube.com",
    )
}
