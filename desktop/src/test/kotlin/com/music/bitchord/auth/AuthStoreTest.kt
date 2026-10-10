package com.music.bitchord.auth

import com.music.bitchord.data.FileStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The encrypted-at-rest registry: the `hasApiSid` gate with its `__Secure-`
 * trap pinned, the upsert/select/remove bookkeeping, and the cipher seams —
 * a pass-through fake for the logic, real DPAPI guarded by an assumption so a
 * non-Windows CI runs everything else and simply skips the scramble.
 *
 * Every store here writes into a throwaway directory. The suite's
 * `LOCALAPPDATA` redirect is the safety net for anything that reaches the
 * [com.music.bitchord.data.AppFiles] defaults by accident, but a test that
 * names its own file never has to rely on it.
 */
class AuthStoreTest {

    private val dir: File = createTempDirectory("bitchord-auth-test").toFile()

    private fun store(cipher: AuthCipher = PassThroughCipher) =
        AuthStore(FileStore(File(dir, "auth.properties")), cipher)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // ---- hasApiSid ----------------------------------------------------------------

    @Test
    fun `a jar with the classic SAPISID can sign requests`() {
        assertTrue(AuthStore.hasApiSid("SID=abc; SAPISID=xyz"))
    }

    @Test
    fun `a jar with only the __Secure- forms can sign requests`() {
        // The partitioned-cookie login: this is the shape the old substring
        // check was wrong about, so both halves of the family are pinned.
        assertTrue(AuthStore.hasApiSid("SID=abc; __Secure-3PAPISID=xyz"))
        assertTrue(AuthStore.hasApiSid("SID=abc; __Secure-1PAPISID=xyz"))
    }

    @Test
    fun `the name must be exact, not a substring`() {
        // "APISID" and "__Host-1PAPISID" contain the old check's needle and
        // are none of the three real names — a jar holding only them is not
        // signed in, however much a `contains("SAPISID")` test believes.
        assertFalse(AuthStore.hasApiSid("__Secure-3PAPISID=; APISID"))
        assertFalse(AuthStore.hasApiSid("__Host-1PAPISID=xyz"))
        // And the match is on the name, case-sensitively, as upstream's set is.
        assertFalse(AuthStore.hasApiSid("sapisid=xyz"))
    }

    @Test
    fun `a named cookie with no value is no secret`() {
        assertFalse(AuthStore.hasApiSid("SAPISID="))
        assertFalse(AuthStore.hasApiSid("SAPISID"))
        assertFalse(AuthStore.hasApiSid("SID=abc"))
    }

    @Test
    fun `one good entry among strangers is enough`() {
        assertTrue(AuthStore.hasApiSid("NID=1; SID=2; SAPISID=3; __Secure-3PAPISID=4"))
    }

    // ---- registry bookkeeping -----------------------------------------------------

    @Test
    fun `upsert replaces by account id and activates by default`() {
        val store = store()
        store.upsertSession(session("a", cookie = "SID=1; SAPISID=x", name = "A"))
        store.upsertSession(session("b", cookie = "SID=2; SAPISID=y", name = "B"))
        store.upsertSession(session("a", cookie = "SID=1.new; SAPISID=x", name = "A2"))

        assertEquals(listOf("b", "a"), store.sessions.map { it.accountId })
        assertEquals("A2", store.sessions.last { it.accountId == "a" }.name)
        assertEquals("a", store.activeAccountId)
        assertEquals("a", store.activeSession?.accountId)
    }

    @Test
    fun `upsert without activate leaves the selection alone`() {
        val store = store()
        store.upsertSession(session("a", cookie = "SID=1; SAPISID=x"))
        store.upsertSession(session("b", cookie = "SID=2; SAPISID=y"), activate = false)
        assertEquals("a", store.activeAccountId)
    }

    @Test
    fun `select moves both halves of the selection`() {
        val store = store()
        store.upsertSession(
            session("a", cookie = "SID=1; SAPISID=x")
                .copy(profiles = listOf(YouTubeProfile("p1", "One"))),
        )
        store.select("a", "p1")
        assertEquals("a", store.activeAccountId)
        assertEquals("p1", store.activeProfileId)
    }

    @Test
    fun `removeAccount answers with the fallback and moves the selection to it`() {
        val store = store()
        store.upsertSession(session("a", cookie = "SID=1; SAPISID=x"))
        store.upsertSession(session("b", cookie = "SID=2; SAPISID=y", name = "B"))

        val fallback = store.removeAccount("a")
        assertEquals("b", fallback?.accountId)
        assertEquals(listOf("b"), store.sessions.map { it.accountId })
        assertEquals("b", store.activeAccountId)
        assertEquals("b", store.activeSession?.accountId)
    }

    @Test
    fun `removeAccount of the last account empties the registry`() {
        val store = store()
        store.upsertSession(session("a", cookie = "SID=1; SAPISID=x"))
        val fallback = store.removeAccount("a")
        assertNull(fallback)
        assertTrue(store.sessions.isEmpty())
        assertNull(store.activeAccountId)
        assertFalse(store.isSignedIn)
    }

    @Test
    fun `signOut clears the registry and the selection`() {
        val store = store()
        store.upsertSession(session("a", cookie = "SID=1; SAPISID=x"))
        assertTrue(store.isSignedIn)
        store.signOut()
        assertTrue(store.sessions.isEmpty())
        assertNull(store.activeAccountId)
        assertNull(store.activeProfileId)
        assertFalse(store.isSignedIn)
    }

    @Test
    fun `a blank selection reads as absent`() {
        val store = store()
        store.upsertSession(session("a", cookie = "SID=1; SAPISID=x"))
        store.select("", null)
        assertNull(store.activeAccountId)
    }

    // ---- cipher seams -------------------------------------------------------------

    @Test
    fun `the registry survives a store restart`() {
        val file = File(dir, "restart.properties")
        val first = AuthStore(FileStore(file), PassThroughCipher)
        first.upsertSession(fullSession())

        val second = AuthStore(FileStore(file), PassThroughCipher)
        assertEquals(listOf(fullSession()), second.sessions)
        assertEquals("a", second.activeAccountId)
    }

    @Test
    fun `an undecryptable store reads as signed out - fail closed`() {
        val file = File(dir, "foreign.properties")
        val first = AuthStore(FileStore(file), PassThroughCipher)
        first.upsertSession(fullSession())

        // The file was copied from another machine: this cipher can unprotect
        // nothing, and the store must hand back no identity rather than a
        // wrong one.
        val second = AuthStore(FileStore(file), RefusingCipher)
        assertTrue(second.sessions.isEmpty())
        assertFalse(second.isSignedIn)
        assertNull(second.activeSession)
        // And the ids survive, dangling but harmless: no reader dereferences
        // them without a session behind them.
        assertEquals("a", second.activeAccountId)
    }

    @Test
    fun `a degraded plain value still round-trips`() {
        // DpapiCipher without touching DPAPI: an unprefixed stored value is
        // one that was written degraded, and reads back as-is.
        assertEquals("SID=abc", DpapiCipher.unprotect("SID=abc"))
        // An unscrambleable prefixed value is absent, not wrong — this also
        // holds on a non-Windows machine, where Crypt32Util simply throws.
        assertNull(DpapiCipher.unprotect("dpapi:not-base64-or-not-dpapi"))
    }

    @Test
    fun `real DPAPI round-trips, where DPAPI exists`() {
        // Guarded rather than asserted: a machine without Crypt32Util (a
        // non-Windows CI) degrades to a plain write, which is itself the
        // documented behaviour — the test then has nothing to pin here.
        val protected = runCatching { DpapiCipher.protect("SID=abc; SAPISID=xyz") }
            .getOrNull()
            ?: return
        if (!protected.startsWith("dpapi:")) return
        assertEquals("SID=abc; SAPISID=xyz", DpapiCipher.unprotect(protected))
    }

    /** The cipher tests run against, so registry logic never depends on DPAPI. */
    private object PassThroughCipher : AuthCipher {
        override fun protect(plain: String): String = plain
        override fun unprotect(stored: String): String? = stored
    }

    /** The other machine's cipher: protects fine, unprotects nothing. */
    private object RefusingCipher : AuthCipher {
        override fun protect(plain: String): String = plain
        override fun unprotect(stored: String): String? = null
    }

    private fun session(
        accountId: String,
        cookie: String,
        name: String = "",
    ): GoogleAccountSession = GoogleAccountSession(
        accountId = accountId,
        cookie = cookie,
        name = name,
        profiles = listOf(YouTubeProfile(profileId = "p-$accountId", name = name.ifBlank { "Personal" })),
        activeProfileId = "p-$accountId",
    )

    private fun fullSession() = session("a", cookie = "SID=1; SAPISID=x", name = "A")
}
