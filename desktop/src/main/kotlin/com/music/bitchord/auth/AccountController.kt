package com.music.bitchord.auth

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The session flows the Android [com.music.bitchord.ui.MainViewModel] carries
 * inside itself, as the standalone object they become once there is no view
 * model big enough to hide them in.
 *
 * It owns three things and nothing else: which Google logins the encrypted
 * registry holds ([accounts]), which of their identities this app is acting
 * as ([activeAccountId]/[activeProfileId]), and the work of getting there —
 * sign-in, profile switch, sign-out. What happens *after* the session moves
 * is deliberately not in here: the home and library reloads are wired from
 * Main.kt through [onSessionChanged], the same seam the Android version fills
 * with its own view-model methods, because which pages need reloading is the
 * shell's knowledge, not the session's.
 *
 * [validator] is the one network call the flows trust — `account()` in
 * production, a fake in tests — handed in like the player controller's
 * engine, for the same reason.
 */
class AccountController(
    private val scope: CoroutineScope,
    private val authStore: AuthStore = AuthStore(),
    /** Verifies a captured cookie/profile pair is really usable before it is kept. */
    private val validator: suspend () -> Result<Account> = { YtMusicRepository.account() },
    /**
     * Lists the identities the API says the login can act as — the detected
     * half of the registry. A seam for the same reason [validator] is: the
     * merge logic runs in tests, the network does not.
     */
    private val channelLister: suspend () -> Result<List<AccountChannel>> =
        { YtMusicRepository.accountChannels() },
) {

    /**
     * The reload the shell does when the listener changed. Set once at startup;
     * invoked after every accepted sign-in, profile switch and sign-out, inside
     * this controller's scope.
     */
    var onSessionChanged: (suspend () -> Unit)? = null

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _accounts = MutableStateFlow<List<GoogleAccountSession>>(emptyList())
    val accounts: StateFlow<List<GoogleAccountSession>> = _accounts.asStateFlow()

    private val _activeAccountId = MutableStateFlow<String?>(null)
    val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    /** The signed-in identity, as YouTube Music reports it. Null when signed out. */
    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private var channels: List<AccountChannel> = emptyList()
    private var channelsLoading = false

    /**
     * Puts the registry on the flows at startup and re-applies the session it
     * names — the launch-time half of sign-in, so a listener who left signed in
     * finds the app still acting as the same identity without opening a browser.
     *
     * The scope is deliberately not adopted here: `ensureSessionScope` fetches
     * the shell lazily on the first request that needs it, and doing it twice
     * is a wasted round-trip on every launch that never touches a signed-in
     * endpoint. The cookie goes in before the channel, always — the cookie
     * setter clears the channel choice, so the reverse order would restore the
     * choice into the value about to be wiped.
     */
    fun restoreActiveSession() {
        val stored = authStore.activeSession ?: return
        if (authStore.activeAccountId == null) {
            authStore.select(stored.accountId, stored.activeProfileId)
        }
        _accounts.value = authStore.sessions
        _activeAccountId.value = stored.accountId
        _activeProfileId.value = stored.activeProfileId
        _signedIn.value = authStore.isSignedIn
        applySession(stored.cookie, stored.profiles.firstOrNull { it.profileId == stored.activeProfileId })
    }

    /**
     * A session lifted out of the login browser — a fresh sign-in, or the same
     * login now pointed at a different channel.
     *
     * One path for both because they differ in exactly one thing: whether the
     * cookie is new. What follows — adopt the identity the captured page
     * reported, remember it, and refetch everything that belongs to a listener
     * — is the same work either way.
     */
    fun onWebSession(
        session: CapturedSession,
        mode: WebSessionMode,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (!session.loggedIn) {
            onComplete(false)
            return
        }
        val oldAccountId = _activeAccountId.value
        val oldProfileId = _activeProfileId.value
        scope.launch {
            // Validate the exact cookie/profile pair before writing any of it.
            // The old implementation persisted first and unconditionally set
            // signedIn=true; a half-finished channel chooser therefore became
            // a durable broken "Personal" account.
            Innertube.cookie = session.cookie
            Innertube.adoptSessionScope(
                pageId = session.pageId,
                dataSyncId = session.dataSyncId,
                authUser = session.authUser,
                visitorData = session.visitorData,
                clientVersion = session.clientVersion,
                loggedIn = true,
            )
            Innertube.selectChannel(session.pageId, session.dataSyncId, session.authUser)

            val account = withTimeoutOrNull(20_000L) {
                var result = validator()
                if (result.isFailure) {
                    delay(750L)
                    result = validator()
                }
                result.getOrNull()
            }
            if (account == null) {
                putRequestSigningBack(oldAccountId, oldProfileId)
                onComplete(false)
                return@launch
            }

            // A channel switch is another identity under the same Google
            // login, never a new Google account. For a fresh sign-in, matching
            // a non-empty email upgrades the existing entry instead of adding
            // a duplicate after cookies rotate.
            val accountId = when (mode) {
                WebSessionMode.SWITCH_CHANNEL -> oldAccountId
                WebSessionMode.SIGN_IN -> authStore.sessions.firstOrNull {
                    account.email.isNotBlank() && it.email.equals(account.email, ignoreCase = true)
                }?.accountId ?: sessionId(session.cookie, null)
            }
            if (accountId == null) {
                putRequestSigningBack(oldAccountId, oldProfileId)
                onComplete(false)
                return@launch
            }
            val previous = authStore.sessions.firstOrNull { it.accountId == accountId }
            val selectedProfileId = profileId(session.pageId, session.dataSyncId, account.name)
            val profile = YouTubeProfile(
                profileId = selectedProfileId,
                name = account.name,
                handle = account.email,
                avatar = account.thumbnailUrl,
                pageId = session.pageId,
                dataSyncId = session.dataSyncId,
                authUser = session.authUser,
                isBrandAccount = session.pageId != null,
            )
            val hasRealIdentity = profile.pageId != null || profile.dataSyncId != null
            val profiles = previous?.profiles.orEmpty().filterNot { known ->
                known.profileId == profile.profileId ||
                    (hasRealIdentity && known.profileId.startsWith("profile:"))
            } + profile
            val stored = GoogleAccountSession(
                accountId = accountId,
                cookie = session.cookie,
                name = account.name,
                email = account.email,
                profiles = profiles,
                activeProfileId = profile.profileId,
            )

            authStore.upsertSession(stored)
            _accounts.value = authStore.sessions
            _activeAccountId.value = accountId
            _activeProfileId.value = profile.profileId
            _account.value = account
            _signedIn.value = true

            // Every resolver verdict and personalised page belongs to the
            // identity that was active before validation succeeded.
            StreamResolver.onSessionChanged()
            LikeState.clear()
            runSessionChangedHook()
            loadChannels(force = true)
            onComplete(true)
        }
    }

    /** Put request signing back exactly as it was after a rejected candidate. */
    private fun putRequestSigningBack(accountId: String?, selectedProfileId: String?) {
        val account = authStore.sessions.firstOrNull { it.accountId == accountId }
        val profile = account?.profiles?.firstOrNull { it.profileId == selectedProfileId }
        Innertube.cookie = account?.cookie
        Innertube.selectChannel(profile?.pageId, profile?.dataSyncId, profile?.authUser)
    }

    /**
     * Applies a registry entry to request signing — cookie first, then the
     * channel, in that order: the cookie setter clears the channel choice, so
     * the reverse order would drop it (see Innertube's setter).
     */
    private fun applySession(cookie: String, profile: YouTubeProfile?) {
        Innertube.cookie = cookie
        Innertube.selectChannel(profile?.pageId, profile?.dataSyncId, profile?.authUser)
    }

    /** Selects an identity without ever allowing a response to replace it. */
    fun selectProfile(accountId: String, selectedProfileId: String, supplied: YouTubeProfile? = null) {
        val source = _accounts.value.firstOrNull { it.accountId == accountId } ?: return
        val profile = supplied ?: source.profiles.firstOrNull { it.profileId == selectedProfileId } ?: return
        if (accountId == _activeAccountId.value && profile.profileId == _activeProfileId.value) return
        val updated = source.copy(
            profiles = (source.profiles.filterNot { it.profileId == profile.profileId } + profile),
            activeProfileId = profile.profileId,
        )
        authStore.upsertSession(updated)
        authStore.select(accountId, profile.profileId)
        _accounts.value = authStore.sessions
        _activeAccountId.value = accountId
        _activeProfileId.value = profile.profileId
        // The cookie is already in hand — no browser, and no validation beyond
        // what the original capture did; this is a bookkeeping act, not a
        // network one.
        applySession(updated.cookie, profile)
        _account.value = null
        StreamResolver.onSessionChanged()
        LikeState.clear()
        runSessionChangedHook()
    }

    fun removeAccount(accountId: String) {
        val fallback = authStore.removeAccount(accountId)
        _accounts.value = authStore.sessions
        if (fallback == null) { signOut(); return }
        val profileId = fallback.activeProfileId
            ?: fallback.profiles.firstOrNull()?.profileId
            ?: return
        selectProfile(fallback.accountId, profileId)
    }

    /**
     * Signs out of YouTube Music only.
     *
     * In a multi-account install, sign out removes only the current Google
     * session and restores another one; with one account it is the familiar
     * full sign-out.
     */
    fun signOut() {
        val current = _activeAccountId.value
        if (current != null && authStore.sessions.size > 1) {
            removeAccount(current)
            return
        }
        authStore.signOut()
        Innertube.cookie = null
        // The mirror image: verdicts reached with a session in hand say nothing
        // about what an anonymous walk will be told, and the clients stood down
        // for refusing the session deserve a fresh hearing without it.
        StreamResolver.onSessionChanged()
        _signedIn.value = false
        _account.value = null
        Innertube.selectChannel(null, null)
        channels = emptyList()
        _accounts.value = emptyList()
        _activeAccountId.value = null
        _activeProfileId.value = null
        // Ratings and playlists belong to the account that just left; keeping
        // them would show the next signed-in user someone else's hearts.
        LikeState.clear()
        runSessionChangedHook()
    }

    /**
     * Loads the channel list, unless it is already in hand.
     *
     * The list itself is not a UI state here — the account dialog reads the
     * registry — so a fetch's only effect is [persistDetectedProfiles]'s merge
     * into the registry, which is how channels YouTube discovers server-side
     * reach the switcher without ever having been captured from a page.
     *
     * @param force refetch even if the list is in hand — after a switch, since
     *   the list itself reports which channel is active.
     */
    fun loadChannels(force: Boolean = false) {
        if (!_signedIn.value) return
        if (channelsLoading) return
        if (!force && channels.isNotEmpty()) return
        channelsLoading = true
        scope.launch {
            runCatching { channelLister() }
                .onSuccess { result ->
                    result.onSuccess { detected ->
                        channels = detected
                        persistDetectedProfiles(detected)
                    }
                }
                .onFailure { Log.w("BitChord", "channel list unavailable: ${it.message}") }
            channelsLoading = false
        }
    }

    /**
     * Merges the channels the API detected into the active account's registry
     * entry — the same reconciliation the Android version runs, placeholder
     * ids and all.
     */
    private fun persistDetectedProfiles(detected: List<AccountChannel>) {
        val accountId = _activeAccountId.value ?: return
        val current = authStore.sessions.firstOrNull { it.accountId == accountId } ?: return
        val profiles = detected.map { channel -> YouTubeProfile(
            profileId(channel.pageId, channel.dataSyncId, channel.name), channel.name,
            channel.subtitle, channel.thumbnailUrl, channel.pageId, channel.dataSyncId,
            isBrandAccount = channel.pageId != null,
        ) }
        // A profile captured before its channel existed carries a provisional,
        // name-hash id (see profileId()) rather than the pageId/dataSyncId Google
        // reports here once the channel is live. Once Google actually reports a
        // channel, that placeholder is stale — it would have been listed here
        // too if it still lacked real ids — and it's dropped rather than kept
        // alongside the identity it was standing in for. An empty response
        // (channel not created yet, or a transient miss) must never drop it.
        val stalePlaceholder = if (profiles.isEmpty()) emptySet() else current.profiles
            .filter { it.profileId.startsWith("profile:") }
            .map { it.profileId }
            .toSet()
        // Keep the captured active identity if Google's endpoint temporarily
        // omits it; an empty/partial response must never erase a selection.
        val merged = current.profiles.filter { known ->
            known.profileId !in stalePlaceholder && profiles.none { it.profileId == known.profileId }
        } + profiles
        val selected = when {
            current.activeProfileId != null && current.activeProfileId !in stalePlaceholder -> current.activeProfileId
            current.activeProfileId in stalePlaceholder -> profiles.singleOrNull()?.profileId
            else -> null
        } ?: merged.firstOrNull()?.profileId
        authStore.upsertSession(
            current.copy(profiles = merged, activeProfileId = selected),
            activate = false,
        )
        _accounts.value = authStore.sessions
    }

    private fun runSessionChangedHook() {
        scope.launch { onSessionChanged?.invoke() }
    }
}
