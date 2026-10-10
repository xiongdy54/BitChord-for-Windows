package com.music.bitchord.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The account registry's data layer: the stored JSON shape, the ids derived
 * from a capture, and the stable ordering the (future) selector swipes read.
 *
 * The shape tests pin the exact field names the Android build has been
 * writing into `google_account_sessions_v2` since v2 — the whole point of the
 * registry is that an upgrade never logs a listener out, which only holds if
 * the names stay byte-identical across the org.json → kotlinx.serialization
 * codec swap.
 */
class AccountSessionsTest {

    private val full = GoogleAccountSession(
        accountId = "acc-1",
        cookie = "SID=abc; SAPISID=xyz",
        name = "Ada Lovelace",
        email = "ada@example.com",
        profiles = listOf(
            YouTubeProfile(
                profileId = "page-1",
                name = "Ada's Brand",
                handle = "@adabrand",
                avatar = "https://example.com/a.png",
                pageId = "page-1",
                dataSyncId = "ds-1",
                authUser = "0",
                isBrandAccount = true,
            ),
            YouTubeProfile(profileId = "ds-2", name = "Personal", dataSyncId = "ds-2"),
        ),
        activeProfileId = "page-1",
    )

    @Test
    fun `a session round-trips with every field`() {
        val decoded = sessionsFromJson(listOf(full).toJson())
        assertEquals(listOf(full), decoded)
    }

    @Test
    fun `the stored shape keeps upstream's field names`() {
        val json = listOf(full).toJson()
        // Account fields.
        assertTrue(json.contains("\"id\":\"acc-1\""), json)
        assertTrue(json.contains("\"cookie\":\"SID=abc; SAPISID=xyz\""), json)
        assertTrue(json.contains("\"name\":\"Ada Lovelace\""), json)
        assertTrue(json.contains("\"email\":\"ada@example.com\""), json)
        assertTrue(json.contains("\"activeProfileId\":\"page-1\""), json)
        // Profile fields — including the `brand` spelling of isBrandAccount.
        assertTrue(json.contains("\"profiles\":[{"), json)
        assertTrue(json.contains("\"handle\":\"@adabrand\""), json)
        assertTrue(json.contains("\"avatar\":\"https://example.com/a.png\""), json)
        assertTrue(json.contains("\"pageId\":\"page-1\""), json)
        assertTrue(json.contains("\"dataSyncId\":\"ds-1\""), json)
        assertTrue(json.contains("\"authUser\":\"0\""), json)
        assertTrue(json.contains("\"brand\":true"), json)
    }

    @Test
    fun `null optional fields are omitted, not written empty`() {
        val bare = YouTubeProfile(profileId = "p", name = "P")
        val json = listOf(
            GoogleAccountSession(accountId = "a", cookie = "SID=x", profiles = listOf(bare)),
        ).toJson()
        assertFalse(json.contains("avatar"), json)
        assertFalse(json.contains("pageId"), json)
        assertFalse(json.contains("authUser"), json)
        assertFalse(json.contains("activeProfileId"), json)
    }

    @Test
    fun `a document that is not a registry decodes as signed out`() {
        assertEquals(emptyList(), sessionsFromJson(null))
        assertEquals(emptyList(), sessionsFromJson(""))
        assertEquals(emptyList(), sessionsFromJson("not json at all"))
    }

    @Test
    fun `an entry without a cookie is not an account`() {
        val json =
            """[{"id":"a","cookie":""},{"id":"b","cookie":"SID=x"}]"""
        val decoded = sessionsFromJson(json)
        assertEquals(listOf("b"), decoded.map { it.accountId })
    }

    @Test
    fun `absent ids fall back to the derived ones`() {
        val json = """[{"cookie":"SID=abc"}]"""
        val decoded = sessionsFromJson(json)
        assertEquals(1, decoded.size)
        assertEquals(sessionId("SID=abc", null), decoded[0].accountId)
        // No profiles key at all is an empty list, not a placeholder profile.
        assertEquals(emptyList<YouTubeProfile>(), decoded[0].profiles)
        val withBareProfile =
            """[{"cookie":"SID=abc","profiles":[{"name":"Personal"}]}]"""
        val profile = sessionsFromJson(withBareProfile)[0].profiles[0]
        assertEquals(profileId(null, null, "Personal"), profile.profileId)
    }

    @Test
    fun `empty strings standing in for absent optional fields read as null`() {
        val json =
            """[{"id":"a","cookie":"SID=x","profiles":[
               {"id":"p","name":"P","avatar":"","pageId":"","dataSyncId":"","authUser":""}]}]"""
        val profile = sessionsFromJson(json)[0].profiles[0]
        assertEquals(null, profile.avatar)
        assertEquals(null, profile.pageId)
        assertEquals(null, profile.dataSyncId)
        assertEquals(null, profile.authUser)
    }

    @Test
    fun `sessionId prefers the dataSyncId and falls back to the cookie hash`() {
        assertEquals("ds-1", sessionId("SID=abc", "ds-1"))
        // A blank dataSyncId is an absent one: a capture with no identity must
        // not name its account two spaces.
        assertEquals(sessionId("SID=abc", null), sessionId("SID=abc", "  "))
        val derived = sessionId("SID=abc", null)
        assertEquals(24, derived.length)
        assertTrue(derived.all { it in "0123456789abcdef" })
        // Deterministic: the same cookie names the same account forever.
        assertEquals(derived, sessionId("SID=abc", null))
    }

    @Test
    fun `profileId prefers the pageId, then the dataSyncId, then a name hash`() {
        assertEquals("page-1", profileId("page-1", "ds-1", "Ada"))
        assertEquals("ds-1", profileId(null, "ds-1", "Ada"))
        val placeholder = profileId(null, null, "Ada")
        // A provisional id is recognisable by its prefix — persistDetectedProfiles
        // drops exactly these once Google reports the real channel.
        assertTrue(placeholder.startsWith("profile:"))
        assertEquals("profile:".length + 16, placeholder.length)
        assertEquals(placeholder, profileId(null, null, "Ada"))
    }

    @Test
    fun `flattened order is account insertion order, then profile order`() {
        val second = GoogleAccountSession(
            accountId = "acc-2",
            cookie = "SID=def",
            profiles = listOf(YouTubeProfile("p2", "Two")),
        )
        val flattened = flattenedProfiles(listOf(full, second))
        assertEquals(
            listOf("acc-1" to "page-1", "acc-1" to "ds-2", "acc-2" to "p2"),
            flattened,
        )
    }

    @Test
    fun `adjacentProfile walks the flat order and stops at the ends`() {
        val second = GoogleAccountSession(
            accountId = "acc-2",
            cookie = "SID=def",
            profiles = listOf(YouTubeProfile("p2", "Two")),
        )
        val accounts = listOf(full, second)
        assertEquals("acc-1" to "ds-2", adjacentProfile(accounts, "acc-1", "page-1", forward = true))
        assertEquals("acc-2" to "p2", adjacentProfile(accounts, "acc-1", "ds-2", forward = true))
        assertEquals(null, adjacentProfile(accounts, "acc-2", "p2", forward = true))
        assertEquals("acc-1" to "page-1", adjacentProfile(accounts, "acc-1", "ds-2", forward = false))
        assertNull(adjacentProfile(accounts, "acc-1", "page-1", forward = false))
        // An unknown current position is index -1, so stepping forward lands
        // on the first entry and stepping back has nothing to reach — the
        // upstream arithmetic, kept verbatim because the selector's
        // wrap-around answer is a user-visible behaviour either way.
        assertEquals("acc-1" to "page-1", adjacentProfile(accounts, "ghost", "p2", forward = true))
        assertNull(adjacentProfile(accounts, "ghost", "p2", forward = false))
    }
}
