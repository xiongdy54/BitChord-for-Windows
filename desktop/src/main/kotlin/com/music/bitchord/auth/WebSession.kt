package com.music.bitchord.auth

/**
 * Value accepted by Innertube as `context.user.onBehalfOfUser`.
 * YouTube commonly exposes `DATASYNC_ID` as `account||delegated`; the second
 * half is the active identity, while plain accounts can leave it empty.
 *
 * All that is left of the Android WebSession on desktop for now — sign-in is a
 * WebView flow and the desktop build browses signed out. Kept in the same
 * package and shape so the ported Innertube code is unchanged.
 */
internal fun normalizeDataSyncId(raw: String?): String? {
    val value = raw?.takeIf { it.isNotBlank() } ?: return null
    if (!value.contains("||")) return value
    return value.substringAfter("||").takeIf { it.isNotBlank() }
        ?: value.substringBefore("||").takeIf { it.isNotBlank() }
}
