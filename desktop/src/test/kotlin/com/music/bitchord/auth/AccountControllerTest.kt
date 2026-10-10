package com.music.bitchord.auth

import com.music.bitchord.data.FileStore
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import com.music.bitchord.data.model.LikeStatus
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session flows: a capture is validated before it is kept, a profile
 * switch never touches the network, and removal/sign-out leave no identity
 * behind — the Android MainViewModel's session section, pinned with a fake
 * validator the way the real one is pinned by the API itself.
 *
 * [Innertube] and [StreamResolver] are process singletons the controller
 * mutates by design; [cleanUp] puts them back to the guest state so the rest
 * of the suite never inherits a session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountControllerTest {

    private val dir: File = createTempDirectory("bitchord-controller-test").toFile()

    /** The validator's scripted answer, and how many times it was consulted. */
    private var validatorCount = 0
    private var nextResult: Result<Account> = Result.success(Account("Ada", "ada@example.com", null))
    private var nextChannels: Result<List<AccountChannel>> = Result.success(emptyList())

    private val authStore = AuthStore(FileStore(File(dir, "auth.properties")), PassThroughCipher)

    private fun controller(scope: TestScope) = AccountController(
        scope.backgroundScope,
        authStore,
        validator = {
            validatorCount++
            nextResult
        },
        channelLister = {
            validatorCount++ // counted with the validator: one "network" counter
            nextChannels
        },
    )

    /**
     * Runs the controller's launched work to completion.
     *
     * Driven with [runCurrent] rather than `advanceUntilIdle` on purpose: the
     * coroutines-test rule is that `advanceUntilIdle` stops the moment only
     * background-scope tasks remain, and every controller act here is a
     * background-scope launch. [advanceTimeBy] covers the capture's 750ms
     * retry beat wherever it sits; the second [runCurrent] catches a launch
     * that chained behind one the first pass just ran.
     */
    private fun TestScope.drain() {
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
    }

    @AfterTest
    fun cleanUp() {
        // The controller and the store share the process with every other
        // test: the guest state is the only state the rest of the suite may
        // assume.
        Innertube.cookie = null
        Innertube.selectChannel(null, null)
        LikeState.clear()
        StreamResolver.onSessionChanged()
        dir.deleteRecursively()
    }

    // ---- captures -----------------------------------------------------------------

    @Test
    fun `a rejected capture changes nothing - not the flows, not the registry, not the cookie`() = runTest {
        val controller = controller(this)
        nextResult = Result.failure(IllegalStateException("Google says stranger"))
        var completed: Boolean? = null
        controller.onWebSession(capture(), WebSessionMode.SIGN_IN) { completed = it }
        drain()

        assertEquals(false, completed)
        assertFalse(controller.signedIn.value)
        assertTrue(controller.accounts.value.isEmpty())
        assertNull(controller.activeAccountId.value)
        assertTrue(authStore.sessions.isEmpty())
        // The candidate cookie was installed for validation and rolled back:
        // request signing is exactly where it was, which is guest.
        assertNull(Innertube.cookie)
        // Validation retried once after its beat, then gave up.
        assertEquals(2, validatorCount)
    }

    @Test
    fun `an accepted capture is applied, stored and signed in`() = runTest {
        val controller = controller(this)
        var completed: Boolean? = null
        controller.onWebSession(capture(), WebSessionMode.SIGN_IN) { completed = it }
        drain()

        assertEquals(true, completed)
        assertTrue(controller.signedIn.value)
        assertEquals(1, controller.accounts.value.size)
        assertEquals(sessionId(CAPTURE_COOKIE, null), controller.activeAccountId.value)
        // pageId is absent, so the derived dataSyncId is the profile.
        assertEquals("ds-1", controller.activeProfileId.value)
        assertEquals("Ada", controller.account.value?.name)
        // The cookie is live request signing now.
        assertEquals(CAPTURE_COOKIE, Innertube.cookie)
        // ...and the registry holds it, with the account's real identity.
        val stored = authStore.activeSession
        assertEquals(CAPTURE_COOKIE, stored?.cookie)
        assertEquals("ada@example.com", stored?.email)
        assertEquals(listOf("ds-1"), stored?.profiles?.map { it.profileId })
    }

    @Test
    fun `a returning email upgrades the existing account instead of duplicating it`() = runTest {
        val controller = controller(this)
        authStore.upsertSession(
            GoogleAccountSession(
                accountId = "existing",
                cookie = "SID=old; SAPISID=o",
                name = "Ada Old",
                email = "Ada@Example.com", // case differs on purpose
                profiles = listOf(YouTubeProfile("p-old", "Old personal")),
                activeProfileId = "p-old",
            ),
        )
        var completed: Boolean? = null
        controller.onWebSession(capture(), WebSessionMode.SIGN_IN) { completed = it }
        drain()

        assertEquals(true, completed)
        assertEquals("existing", controller.activeAccountId.value)
        assertEquals("ds-1", controller.activeProfileId.value)
        // The old profile survives beside the fresh capture: a sign-in is an
        // account-level event, not an identity wipe.
        val profiles = authStore.activeSession?.profiles.orEmpty()
        assertEquals(listOf("p-old", "ds-1"), profiles.map { it.profileId })
    }

    @Test
    fun `a capture with no signed-in identity is refused before the network`() = runTest {
        val controller = controller(this)
        var completed: Boolean? = null
        controller.onWebSession(
            capture().copy(loggedIn = false),
            WebSessionMode.SIGN_IN,
        ) { completed = it }
        drain()

        assertEquals(false, completed)
        assertEquals(0, validatorCount)
        assertTrue(authStore.sessions.isEmpty())
    }

    // ---- profile switching ----------------------------------------------------------

    @Test
    fun `selectProfile switches without revalidation - the cookie is already in hand`() = runTest {
        val controller = controller(this)
        seedTwoAccounts()
        controller.restoreActiveSession()
        assertEquals("a", controller.activeAccountId.value)
        val before = validatorCount

        controller.selectProfile("b", "p-b")
        drain()

        assertEquals("b", controller.activeAccountId.value)
        assertEquals("p-b", controller.activeProfileId.value)
        assertEquals(ACCOUNT_B_COOKIE, Innertube.cookie)
        // A switch is bookkeeping, not a network act.
        assertEquals(before, validatorCount)
        assertEquals("b", authStore.activeSession?.accountId)
    }

    @Test
    fun `selectProfile moves the chosen profile to the end of its account`() = runTest {
        val controller = controller(this)
        seedTwoAccounts()
        controller.restoreActiveSession()
        controller.selectProfile("a", "p-a2")
        drain()

        val account = authStore.sessions.single { it.accountId == "a" }
        assertEquals(listOf("p-a1", "p-a2"), account.profiles.map { it.profileId })
        assertEquals("p-a2", account.activeProfileId)
    }

    // ---- removal and sign-out -------------------------------------------------------

    @Test
    fun `removeAccount falls back to another account`() = runTest {
        val controller = controller(this)
        seedTwoAccounts()
        controller.restoreActiveSession()

        controller.removeAccount("a")
        drain()

        assertEquals(listOf("b"), authStore.sessions.map { it.accountId })
        assertEquals("b", controller.activeAccountId.value)
        assertEquals(ACCOUNT_B_COOKIE, Innertube.cookie)
        assertTrue(controller.signedIn.value) // still signed in, via b
    }

    @Test
    fun `signOut with one account clears everything, including like overrides`() = runTest {
        val controller = controller(this)
        seedTwoAccounts()
        controller.restoreActiveSession()
        controller.removeAccount("a") // now single-account
        LikeState.set("vid-1", LikeStatus.LIKE)

        controller.signOut()
        drain()

        assertFalse(controller.signedIn.value)
        assertTrue(controller.accounts.value.isEmpty())
        assertTrue(authStore.sessions.isEmpty())
        assertNull(controller.activeAccountId.value)
        assertNull(Innertube.cookie)
        assertTrue(LikeState.overrides.value.isEmpty())
    }

    @Test
    fun `signOut in a multi-account install removes only the current session`() = runTest {
        val controller = controller(this)
        seedTwoAccounts()
        controller.restoreActiveSession()

        controller.signOut()
        drain()

        assertEquals(listOf("b"), authStore.sessions.map { it.accountId })
        assertEquals("b", controller.activeAccountId.value)
    }

    // ---- detected channels ------------------------------------------------------------

    @Test
    fun `detected channels replace their placeholder and keep real ids`() = runTest {
        val controller = controller(this)
        // A capture that arrived before the brand channel existed: its
        // provisional, name-hash profile is what the registry holds.
        authStore.upsertSession(
            GoogleAccountSession(
                accountId = "a",
                cookie = "SID=1; SAPISID=x",
                name = "A",
                profiles = listOf(YouTubeProfile("profile:placeholder", "Brand", dataSyncId = "ds-old")),
                activeProfileId = "profile:placeholder",
            ),
        )
        controller.restoreActiveSession()
        nextChannels = Result.success(
            listOf(
                AccountChannel("Brand", "@brand", null, "page-1", "ds-1", activeOnWeb = false),
                AccountChannel("Personal", "@personal", null, null, "ds-2", activeOnWeb = true),
            ),
        )
        controller.loadChannels(force = true)
        drain()

        val stored = authStore.sessions.single { it.accountId == "a" }
        // The placeholder is gone now that Google reports the real channel.
        assertFalse(stored.profiles.any { it.profileId.startsWith("profile:") })
        assertEquals(listOf("page-1", "ds-2"), stored.profiles.map { it.profileId })
        // The merge writes the entry's own selection, not the store's global
        // keys — upsert ran with activate=false, exactly as upstream's
        // persistDetectedProfiles does; the global keys move only on select.
        assertEquals("page-1", stored.activeProfileId)
        assertEquals("profile:placeholder", authStore.activeProfileId)
    }

    @Test
    fun `an empty channel response never drops a captured identity`() = runTest {
        val controller = controller(this)
        authStore.upsertSession(
            GoogleAccountSession(
                accountId = "a",
                cookie = "SID=1; SAPISID=x",
                name = "A",
                profiles = listOf(YouTubeProfile("profile:placeholder", "Brand", dataSyncId = "ds-old")),
                activeProfileId = "profile:placeholder",
            ),
        )
        controller.restoreActiveSession()
        nextChannels = Result.success(emptyList())
        controller.loadChannels(force = true)
        drain()

        val stored = authStore.sessions.single { it.accountId == "a" }
        assertEquals(listOf("profile:placeholder"), stored.profiles.map { it.profileId })
        assertEquals("profile:placeholder", authStore.activeProfileId)
    }

    // ---- fixtures ----------------------------------------------------------------

    private fun seedTwoAccounts() {
        authStore.upsertSession(
            GoogleAccountSession(
                accountId = "a",
                cookie = ACCOUNT_A_COOKIE,
                name = "A",
                email = "a@example.com",
                profiles = listOf(
                    YouTubeProfile("p-a1", "A personal"),
                    YouTubeProfile("p-a2", "A brand", pageId = "page-a2"),
                ),
                activeProfileId = "p-a1",
            ),
        )
        authStore.upsertSession(
            GoogleAccountSession(
                accountId = "b",
                cookie = ACCOUNT_B_COOKIE,
                name = "B",
                email = "b@example.com",
                profiles = listOf(YouTubeProfile("p-b", "B personal")),
                activeProfileId = "p-b",
            ),
        )
        // The last upsert activated b; every test here starts from a.
        authStore.select("a", "p-a1")
    }

    private fun capture() = CapturedSession(
        cookie = CAPTURE_COOKIE,
        pageId = null,
        dataSyncId = "ds-1",
        authUser = "0",
        visitorData = "visitor",
        clientVersion = "1.0",
        loggedIn = true,
    )

    private companion object {
        const val CAPTURE_COOKIE = "SID=abc; SAPISID=xyz"
        const val ACCOUNT_A_COOKIE = "SID=aaa; SAPISID=x"
        const val ACCOUNT_B_COOKIE = "SID=bbb; SAPISID=y"
    }

    /** The cipher the tests run against, so registry logic never needs DPAPI. */
    private object PassThroughCipher : AuthCipher {
        override fun protect(plain: String): String = plain
        override fun unprotect(stored: String): String? = stored
    }
}
