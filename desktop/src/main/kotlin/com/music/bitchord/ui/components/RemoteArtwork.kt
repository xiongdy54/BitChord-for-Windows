package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.music.bitchord.data.model.Song

/**
 * A drawable art URL for [song]: its own thumbnail when it has one, else the
 * embedded picture pulled out of the remote file and cached — else null.
 *
 * The extraction only ever runs for remote-library tracks with no thumbnail;
 * every other row resolves synchronously to what it already had, so this is
 * safe to call from shared components like [SongRow] that draw every library
 * in the app. Keyed on the track id, so a recycled row re-resolves instead
 * of flashing a stranger's cover.
 *
 * The extraction half is a scope boundary: it walks the WebDAV/SMB file for an
 * embedded picture through RemoteArtworkStore, which comes over with the remote
 * libraries in a later slice. Until then a remote row with no thumbnail simply
 * has none.
 */
@Composable
fun rememberRemoteArtworkUrl(song: Song?): String? {
    val key = song?.videoId
    val resolved by remember(key) { mutableStateOf(song?.thumbnailUrl) }
    LaunchedEffect(key) {
        // Intentionally empty — see the note above.
    }
    return resolved
}
