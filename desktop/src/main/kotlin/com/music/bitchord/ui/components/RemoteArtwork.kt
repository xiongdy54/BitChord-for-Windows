package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.RemoteArtworkStore

/**
 * A drawable art URL for [song]: its own thumbnail when it has one, else the
 * embedded picture pulled out of the file and cached — else null.
 *
 * The extraction only ever runs for tracks with no thumbnail — a WebDAV row
 * whose cover lives inside the file, or a local scan row whose sidecar folder
 * never had one — and it goes through [RemoteArtworkStore]: memory first,
 * then a disk cache, then the bounded ranged reads (local offset reads for a
 * path, HTTP ranges for a `webdav:` track's URL). Every other row resolves
 * synchronously to what it already had, so this is safe to call from shared
 * components like [SongRow] that draw every library in the app. Keyed on the
 * track id, so a recycled row re-resolves instead of flashing a stranger's
 * cover.
 *
 * This is the seam the slice-9 comment promised would fill in "when the
 * remote libraries arrive" — slice 11 is that slice.
 */
@Composable
fun rememberRemoteArtworkUrl(song: Song?): String? {
    val key = song?.videoId
    var resolved by remember(key) { mutableStateOf(song?.thumbnailUrl) }
    LaunchedEffect(key) {
        val current = song ?: return@LaunchedEffect
        if (current.thumbnailUrl != null) return@LaunchedEffect
        // Never throws — a track whose picture cannot be extracted simply
        // keeps the placeholder it already drew.
        val url = runCatching { RemoteArtworkStore.resolveArt(current) }.getOrNull()
        if (url != null) resolved = url
    }
    return resolved
}
