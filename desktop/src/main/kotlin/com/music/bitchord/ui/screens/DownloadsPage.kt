package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.download.Downloads
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.downloads_empty
import com.music.bitchord.ui.components.SongRow
import org.jetbrains.compose.resources.stringResource

/**
 * The tracks that are on this machine, newest first.
 *
 * The page is a snapshot the record refreshes: every download finishing and
 * every delete changes [Downloads.saved], and the list is re-read off disk for
 * each — the loader verifies every file it names, so a row here is a file that
 * was there when the page was drawn. (The disk walk is bounded by the record,
 * not by the folder: nothing scans.)
 *
 * The rows are the app's ordinary ones, wrapped in the ordinary right-click —
 * whose download section now offers "Delete download" for every row here, and
 * whose click plays straight off the file. There is no separate offline
 * playback mode to enter and no queue semantics of their own: downloaded tracks
 * are tracks, wherever they were fetched from.
 */
@Composable
fun DownloadsPage(
    onPlayFrom: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val record by Downloads.saved.collectAsState()
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(record.keys) {
        songs = Downloads.getDownloadedSongs()
            .sortedByDescending { it.localDateAddedSeconds ?: 0L }
    }

    val list = songs
    if (list == null) {
        // The first read is a stat per record — quick, but not composition work.
        return
    }
    if (list.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.downloads_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(state = rememberLazyListState(), modifier = modifier.fillMaxSize()) {
        itemsIndexed(list) { index, song ->
            SongRow(
                song = song,
                onClick = { onPlayFrom(list, index) },
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            )
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}
