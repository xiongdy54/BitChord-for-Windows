// Ported from app/src/main/java/com/music/bitchord/data/AppUpdateChecker.kt —
// the poll shape, the version arithmetic and the "what's new" notes are the
// original's. What the desktop changes:
//   - The repo is the fork's (`xiongdy54/BitChord-for-Windows`): upstream's
//     releases are Android APKs, and a desktop build comparing itself against
//     them would read as permanently out of date against versions that were
//     never for it.
//   - No download-and-install: the desktop build ships as an MSI/EXE the user
//     installs, so the update *action* is the release page in the browser —
//     the honest desktop form of "hand it to the system installer".
package com.music.bitchord.data

import com.music.bitchord.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * BitChord ships off GitHub Releases rather than through a store, so there's
 * nothing to push an update notice on its own — this polls the repo's "latest
 * release" once per launch (and whenever the settings row asks) and compares
 * its tag against the running build.
 */
object AppUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        /** The release's own Markdown body, shown as this update's "what's new". */
        val notes: String?,
    )

    /**
     * The fork's own releases. Overridable for a build that ships from
     * somewhere else — the value is a URL, not a promise.
     */
    internal var latestReleaseUrl: String =
        "https://api.github.com/repos/xiongdy54/BitChord-for-Windows/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /**
     * Ask GitHub what the newest release is. Every failure — no release yet, a
     * 404, a network wall — is silence: an update check that cannot run is not
     * something the user asked to hear about, so the flow simply stays null.
     */
    suspend fun check() = withContext(Dispatchers.IO) {
        _checking.value = true
        try {
            runCatching {
                val request = Request.Builder().url(latestReleaseUrl).build()
                val body = Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()
                } ?: return@runCatching
                val release = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching
                val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                val notes = release["body"]?.jsonPrimitive?.contentOrNull
                val latest = tag.removePrefix("v")
                if (isNewer(latest, BuildConfig.VERSION_NAME)) {
                    _available.value = UpdateInfo(latest, url, notes)
                }
            }
        } finally {
            _checking.value = false
        }
    }

    /** A version split into its numeric dotted parts and whether it carries a "-suffix" (e.g. "-desktop", "-beta2"). */
    private data class ParsedVersion(val parts: List<Int>, val isPreRelease: Boolean)

    private fun parseVersion(raw: String): ParsedVersion {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        return ParsedVersion(base.split(".").map { it.toIntOrNull() ?: 0 }, dash >= 0)
    }

    /**
     * Numeric, dot-separated comparison — "1.10" outranks "1.9" — with one
     * extra rule carried over from upstream: a "-suffix" build (the desktop's
     * `0.1.0-desktop`, a beta's `-beta2`) is treated as older than a plain
     * release of the same numbers, since the suffix by definition names a build
     * that predates the tag it was cut toward. Without this, a desktop build
     * and the release it matches compare equal and the notice never shows.
     */
    internal fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest)
        val c = parseVersion(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.isPreRelease && !l.isPreRelease
    }
}
