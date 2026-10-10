package com.music.bitchord.auth

import com.music.bitchord.data.AppFiles
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.FileStore
import java.util.Base64

/**
 * The scramble the session registry goes through on its way to disk.
 *
 * Injected rather than hardcoded so the registry's tests can run with a
 * pass-through fake and never touch real DPAPI — the same seam every other
 * platform call in this build gets.
 */
interface AuthCipher {
    /** [plain] in a form safe to put in a properties file. */
    fun protect(plain: String): String

    /** The original text, or null when it cannot be recovered — never a wrong value. */
    fun unprotect(stored: String): String?
}

/**
 * Windows DPAPI, user scope, via the jna-platform that is already on the
 * classpath (vlcj drags it in for its own Win32 needs).
 *
 * Encrypted values carry the `dpapi:` prefix; a value without it is one that
 * was written degraded (see [protect]'s catch) and is passed through as-is.
 * The two failure modes are deliberately asymmetric:
 *
 *  * **protect fails** — no Crypto32 in a theoretical non-Windows branch —
 *    and the value is stored plain. The Android original degrades to plain
 *    prefs for the same reason: a credential saved unencrypted is a lesser
 *    harm than an app that cannot sign in at all.
 *  * **unprotect fails** — the file was copied from another machine or
 *    another Windows user, where DPAPI's scope makes it undecryptable —
 *    returns null, and every reader above treats that as "no session".
 *    Fail-closed: the file must not hand back an identity that cannot be
 *    what it claims. A degraded-plain value still round-trips, which is why
 *    only the prefix branch can fail.
 */
object DpapiCipher : AuthCipher {
    private const val PREFIX = "dpapi:"

    override fun protect(plain: String): String = try {
        PREFIX + Base64.getEncoder().encodeToString(
            com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(
                plain.toByteArray(Charsets.UTF_8),
            ),
        )
    } catch (error: Throwable) {
        Log.w("BitChord", "credential protection unavailable, storing plain: $error")
        plain
    }

    override fun unprotect(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            String(
                com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(
                    Base64.getDecoder().decode(stored.removePrefix(PREFIX)),
                ),
                Charsets.UTF_8,
            )
        } catch (error: Throwable) {
            Log.w("BitChord", "stored credentials are not decryptable here; treating as absent: $error")
            null
        }
    }
}

/**
 * Encrypted-at-rest storage for the YouTube Music session registry — the
 * desktop stand-in for the Android AuthStore, whose Keystore-backed prefs
 * have no Windows equivalent this build wants to manage itself. The value at
 * risk is a Google session cookie: it grants full access to the account, so
 * it does not go in the plain `settings.properties` the settings live in —
 * the same split the Android app draws between AuthStore and SharedPreferences.
 *
 * The registry JSON is wrapped by [cipher] before it touches the file. The
 * active-account/profile ids are plain: neither is a secret, and a registry
 * the cipher cannot open (another machine's file) reads as signed out with
 * dangling ids — harmless, because nothing dereferences them without a
 * session behind it.
 *
 * Only the session surface is ported. The Android class's Discord/WebDAV/SMB
 * tokens and the legacy single-cookie entry belong to features their own
 * slices have not carried to desktop yet, and the channel keys died with
 * `v2` on the phone: the multi-account registry is where a chosen channel
 * lives now.
 */
class AuthStore(
    private val store: FileStore = FileStore(AppFiles.file("auth.properties")),
    private val cipher: AuthCipher = DpapiCipher,
) {

    /** The durable account registry — one [GoogleAccountSession] per Google login. */
    val sessions: List<GoogleAccountSession>
        get() = sessionsFromJson(
            store.getStringOrNull(KEY_SESSIONS)?.let(cipher::unprotect),
        )

    var activeAccountId: String?
        get() = store.getStringOrNull(KEY_ACTIVE_ACCOUNT)?.ifBlank { null }
        private set(value) = writeKey(KEY_ACTIVE_ACCOUNT, value)

    var activeProfileId: String?
        get() = store.getStringOrNull(KEY_ACTIVE_PROFILE)?.ifBlank { null }
        private set(value) = writeKey(KEY_ACTIVE_PROFILE, value)

    /**
     * Absent and blank mean the same thing in this store: the upstream writer
     * could only produce a blank here through `putString(key, "")`, and no
     * reader of theirs ever distinguished it from removed.
     */
    private fun writeKey(key: String, value: String?) {
        if (value.isNullOrEmpty()) store.remove(key) else store.putString(key, value)
    }

    /** The account to restore on launch — the active one, or the only one there is. */
    val activeSession: GoogleAccountSession?
        get() = sessions.firstOrNull { it.accountId == activeAccountId }
            ?: sessions.firstOrNull()

    fun replaceSessions(value: List<GoogleAccountSession>) {
        store.putString(KEY_SESSIONS, cipher.protect(value.toJson()))
    }

    /** Inserts or replaces one login; [activate] moves the selection with it. */
    fun upsertSession(session: GoogleAccountSession, activate: Boolean = true) {
        val next = sessions.filterNot { it.accountId == session.accountId } + session
        replaceSessions(next)
        if (activate) select(session.accountId, session.activeProfileId)
    }

    fun select(accountId: String, profileId: String?) {
        activeAccountId = accountId
        activeProfileId = profileId
    }

    /**
     * Forgets one login and answers with the one that should take its place.
     * The selection moves to the fallback even when the caller goes on to sign
     * out entirely — a selection naming a removed account is a dangling state
     * no reader should have to defend against.
     */
    fun removeAccount(accountId: String): GoogleAccountSession? {
        val remaining = sessions.filterNot { it.accountId == accountId }
        replaceSessions(remaining)
        val fallback = remaining.firstOrNull()
        select(fallback?.accountId.orEmpty(), fallback?.activeProfileId)
        return fallback
    }

    /** A registry is only "signed in" when the active cookie can sign requests. */
    val isSignedIn: Boolean
        get() = activeSession?.cookie?.let { hasApiSid(it) } == true

    /**
     * Signs out of YouTube Music entirely: registry and selection go together.
     *
     * The browser keeps its own copy of the Google login, and a sign-out that
     * leaves it in place is not one: the next sign-in is waved straight through
     * as the account just signed out of, with no opportunity to choose another.
     * See [BrowserSession]. Best effort — a browser that never started has no
     * jar to clear, and that is a convenience loss, not a failure.
     */
    fun signOut() {
        replaceSessions(emptyList())
        select("", null)
        BrowserSession.clearGoogleCookies()
    }

    companion object {
        /**
         * Whether a cookie header carries a secret Innertube requests can be
         * signed with.
         *
         * Matched on the cookie *name*, which reads as pedantry and is not. The
         * test used to be `cookie.contains("SAPISID")`, and `__Secure-3PAPISID`
         * contains "SAPISID" — so a jar holding only the `__Secure-` forms, which
         * is what a partitioned-cookie login produces, passed a check for a
         * cookie it did not have. The app then declared itself signed in and made
         * every request unsigned, which Google answers as a stranger. Library
         * reads degraded quietly and history was never written at all.
         */
        fun hasApiSid(cookieHeader: String): Boolean =
            cookieHeader.split(';').any { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                name in API_SID_NAMES && value.isNotEmpty()
            }

        private val API_SID_NAMES =
            setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

        private const val KEY_SESSIONS = "google_account_sessions_v2"
        private const val KEY_ACTIVE_ACCOUNT = "active_google_account_id_v2"
        private const val KEY_ACTIVE_PROFILE = "active_youtube_profile_id_v2"
    }
}
