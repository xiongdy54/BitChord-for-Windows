package com.music.bitchord.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * A Google login kept independently from every other captured login.
 *
 * Ported verbatim from the Android app; only the JSON codec moved from
 * org.json to kotlinx.serialization (see [toJson]) while the stored shape —
 * the `google_account_sessions_v2` value and the field names inside it —
 * stays byte-compatible, so a listener who installs the desktop build over
 * nothing finds the same promises an upgrade never logs them out.
 */
@Serializable
data class GoogleAccountSession(
    /**
     * Blank, not required-without-default: the upstream reader derived an id
     * for a document that omitted it, and a strict decoder would throw the
     * whole registry away instead — see [sessionsFromJson].
     */
    @SerialName("id") val accountId: String = "",
    val cookie: String,
    val name: String = "",
    val email: String = "",
    val profiles: List<YouTubeProfile> = emptyList(),
    val activeProfileId: String? = null,
)

/** One Personal or Brand identity available under a Google login. */
@Serializable
data class YouTubeProfile(
    /** Blank-default for the same reason [GoogleAccountSession.accountId] is. */
    @SerialName("id") val profileId: String = "",
    val name: String,
    val handle: String = "",
    val avatar: String? = null,
    val pageId: String? = null,
    val dataSyncId: String? = null,
    val authUser: String? = null,
    @SerialName("brand") val isBrandAccount: Boolean = false,
)

/** Stable selector/swipe order: account insertion order, then profile order. */
fun flattenedProfiles(accounts: List<GoogleAccountSession>): List<Pair<String, String>> =
    accounts.flatMap { account -> account.profiles.map { account.accountId to it.profileId } }

fun adjacentProfile(
    accounts: List<GoogleAccountSession>, accountId: String?, profileId: String?, forward: Boolean,
): Pair<String, String>? {
    val items = flattenedProfiles(accounts)
    val index = items.indexOf(accountId to profileId)
    return items.getOrNull(index + if (forward) 1 else -1)
}

internal fun sessionId(cookie: String, dataSyncId: String?): String =
    dataSyncId?.takeIf { it.isNotBlank() } ?: sha256(cookie).take(24)

internal fun profileId(pageId: String?, dataSyncId: String?, name: String): String =
    pageId ?: dataSyncId ?: "profile:${sha256(name).take(16)}"

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }

/**
 * The codec, deliberately tuned to what org.json produced upstream: every
 * non-null field is written (the same always-written rows the Android encoder
 * produced), nulls are omitted exactly as `JSONObject.put(key, null)` dropped
 * them, and unknown keys are ignored on the way back so an older or newer
 * writer cannot break a read. The stringy option keeps a hand-edited or
 * half-written value decodable rather than fatal.
 */
private val sessionJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

internal fun List<GoogleAccountSession>.toJson(): String =
    sessionJson.encodeToString(ListSerializer(GoogleAccountSession.serializer()), this)

/**
 * The reverse of [toJson], with the upstream reader's forgiveness carried
 * across: an entry whose cookie is blank is skipped (an entry that cannot sign
 * a request is not an account), absent ids fall back to the derived ones (the
 * same defaults [sessionId] and [profileId] give the encoder on capture), and
 * empty strings standing in for absent optional fields are read as null. A
 * document that is not a list at all yields an empty registry — a corrupt
 * store means signed out, never a crash on launch.
 */
internal fun sessionsFromJson(raw: String?): List<GoogleAccountSession> = runCatching {
    sessionJson.decodeFromString(ListSerializer(GoogleAccountSession.serializer()), raw ?: "[]")
}.getOrDefault(emptyList())
    .filter { it.cookie.isNotBlank() }
    .map { session ->
        session.copy(
            accountId = session.accountId.ifBlank { sessionId(session.cookie, null) },
            profiles = session.profiles.map { profile ->
                profile.copy(
                    profileId = profile.profileId.ifBlank { profileId(null, null, profile.name) },
                    avatar = profile.avatar?.ifBlank { null },
                    pageId = profile.pageId?.ifBlank { null },
                    dataSyncId = profile.dataSyncId?.ifBlank { null },
                    authUser = profile.authUser?.ifBlank { null },
                )
            },
        )
    }
