package com.music.bitchord.auth

import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cef.callback.CefCookieVisitor
import org.cef.callback.CefCompletionCallback
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * less convenient, never a reason for signing out to crash. Every call is
 * `suspend` and hops to IO for its waits, because none of the manager's
 * answers are synchronous in this CEF — see [visitCookiesAwait] — and no
 * caller should have to know that.
 */
object BrowserSession {

    /**
     * How long any single cookie round-trip may take. On a live browser the
     * answer arrives in milliseconds — the bound exists for one that has
     * stopped answering at all, and a timeout degrades to whatever the
     * visitor managed to deliver.
     */
    private const val COOKIE_WAIT_TIMEOUT_MS = 4_000L

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
    suspend fun installGoogleCookies(cookieHeader: String) {
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
        // The sets queue in issue order, so an answered flush means they have
        // all landed — the browser opened next loads its page against the
        // account it was pointed at, not whichever write was last in flight.
        flushStoreAwait(jar)
        Log.d("BitChord", "restored $installed browser cookies for the selected account")
    }

    /**
     * Forgets the Google login the login browser is holding.
     *
     * Google's cookies only, rather than wiping the whole store: the same jar
     * will hold whatever other logins later slices park in it, and signing
     * out of YouTube Music is not a reason to sign out of those. One visit
     * over the whole jar names them — every cookie carries the domain it must
     * be deleted under, and `deleteCookies` given that origin and the name
     * removes both the host-only and the domain-wide form, which the expired
     * overwrites this used to pair with it only duplicated. HttpOnly cookies
     * are visited too — CEF can list them, and a jar cleared except for its
     * HttpOnly half is half cleared.
     *
     * The visit and the deletes are all asynchronous, so the flush at the end
     * is the barrier: the jar works its queue in order, and an answered flush
     * means every delete has landed before the sign-in opened next gets a
     * chance to be waved through on a cookie that survived.
     */
    suspend fun clearGoogleCookies() {
        val jar = manager() ?: return
        val stale = mutableListOf<Pair<String, String>>() // origin host, name
        withContext(Dispatchers.IO) {
            visitCookiesAwait(jar, null) { cookie, _, _, _ ->
                if (GOOGLE_DOMAINS.any { isUnderDomain(cookie.domain, it) }) {
                    stale += cookie.domain.removePrefix(".") to cookie.name
                }
                true
            }
            stale.forEach { (host, name) -> jar.deleteCookies("https://$host", name) }
            flushStoreAwait(jar)
        }
        Log.d("BitChord", "cleared ${stale.size} browser cookies for Google")
    }

    /**
     * Reads every cookie the jar holds for the Music origin — HttpOnly ones
     * included, since the signing secret is one of them. The visit is awaited
     * (see [visitCookiesAwait]) and the flush it issues leaves the store
     * written: the dialog is usually closing in the next frame, and a cookie
     * jar written after that is a jar the next sign-in reads instead of this
     * one.
     *
     * @return a cookie header in the `name=value; …` form the registry stores,
     *   or null when the jar has nothing (or no jar exists).
     */
    suspend fun readMusicCookies(): String? {
        val jar = manager() ?: return null
        val entries = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            visitCookiesAwait(jar, MUSIC_ORIGIN) { cookie, _, _, _ ->
                entries += "${cookie.name}=${cookie.value}"
                true
            }
        }
        return entries.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /**
     * Visits [jar]'s cookies — every cookie the jar holds for [url] when given
     * one (HttpOnly included), or the whole jar when not — handing each to
     * [onCookie], and waits for the visit to have answered.
     *
     * No cookie-manager call is synchronous in this CEF. The manager hands
     * the request to the browser and returns before a single cookie has been
     * delivered; the visitor is invoked later, on a CEF thread, once the
     * jar's store answers. Reading the collection right after the call — as
     * the first cut of every caller here did — always saw it empty, which is
     * how a capture from a signed-in page could report "no signed-in
     * profile". The wait ends on the first of:
     *
     *  * the visitor's own last call (`count == total - 1`) — the answer was
     *    non-empty and is now fully in hand; or
     *  * a flush issued once the visit is in flight. The jar works its queue
     *    in order, so the flush cannot be answered before the visit's cookies
     *    are delivered — and it is the only over-signal an *empty* answer
     *    gets, because CEF never invokes the visitor when there is nothing
     *    to visit.
     *
     * [COOKIE_WAIT_TIMEOUT_MS] bounds a browser that has stopped answering at
     * all. Blocks the calling thread; reached on IO.
     */
    private fun visitCookiesAwait(
        jar: CefCookieManager,
        url: String?,
        onCookie: CefCookieVisitor,
    ) {
        val answered = CountDownLatch(1)
        // The size the visitor was promised, -1 until its first call. Both
        // this and the flush callback below run on the browser's UI thread.
        var promised = -1
        val visitor = CefCookieVisitor { cookie, count, total, deleteCookie ->
            promised = total
            val keep = onCookie.visit(cookie, count, total, deleteCookie)
            if (count >= total - 1) answered.countDown()
            keep
        }
        if (url == null) jar.visitAllCookies(visitor) else jar.visitUrlCookies(url, true, visitor)
        jar.flushStore(CefCompletionCallback {
            // A non-empty visit is ended by its own last call; the flush only
            // speaks for the empty one.
            if (promised <= 0) answered.countDown()
        })
        answered.await(COOKIE_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Issues a flush and waits for the jar to answer it — the barrier that
     * orders the deletes or sets issued just before: the jar works its queue
     * in order, so an answered flush means they have all landed. Same bound
     * and best-effort spirit as [visitCookiesAwait]; blocks the calling
     * thread.
     */
    private fun flushStoreAwait(jar: CefCookieManager) {
        val flushed = CountDownLatch(1)
        jar.flushStore(CefCompletionCallback { flushed.countDown() })
        flushed.await(COOKIE_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** Whether [domain] is [base] itself or a subdomain of it. */
    private fun isUnderDomain(domain: String, base: String): Boolean {
        val host = domain.removePrefix(".")
        return host == base || host.endsWith(".$base")
    }

    private val epoch = Date(0)

    private const val MUSIC_ORIGIN = "https://music.youtube.com"

    /** The base domains a cookie must sit under to count as Google's. */
    private val GOOGLE_DOMAINS = listOf("youtube.com", "google.com")

    private val YOUTUBE_ORIGINS = listOf(
        "https://music.youtube.com",
        "https://www.youtube.com",
        "https://youtube.com",
    )
}
