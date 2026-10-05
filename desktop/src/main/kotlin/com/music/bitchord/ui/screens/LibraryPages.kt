package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.library_empty
import com.music.bitchord.desktop.resources.library_sign_in_description
import com.music.bitchord.desktop.resources.retry
import com.music.bitchord.desktop.resources.history_empty
import com.music.bitchord.ui.LibraryViewModel
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.libraryGrid
import com.music.bitchord.ui.shell.PAGE_TOP_GUTTER
import org.jetbrains.compose.resources.stringResource

/**
 * The three library pages the sidebar names — songs, playlists, recently
 * added — over the state [LibraryViewModel] keeps for each. They are list and
 * grid pages, not screens of their own; Apple Music's library is exactly this
 * trio at desktop width, and the pieces are the app's own rows and grids.
 */

/** Error and empty share a slot; a retry is offered only when a retry could help. */
@Composable
private fun LibraryNotice(
    message: String,
    detail: String? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            detail?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }
            if (onRetry != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(Res.string.retry),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onRetry() }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun <T> LibraryState(
    state: UiState<T>,
    ensure: () -> Unit,
    retry: () -> Unit,
    emptyMessage: String,
    content: @Composable (T) -> Unit,
) {
    LaunchedEffect(Unit) { ensure() }
    when (val value = state) {
        is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is UiState.Error -> LibraryNotice(
            message = value.message,
            onRetry = retry,
            modifier = Modifier.fillMaxSize(),
        )

        is UiState.Success ->
            if (value.data.let { it is List<*> && it.isEmpty() }) {
                LibraryNotice(
                    message = emptyMessage,
                    detail = stringResource(Res.string.library_sign_in_description),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                content(value.data)
            }
    }
}

/** The account's songs: liked music first, then what was explicitly added to the library. */
@Composable
fun LibrarySongsPage(
    viewModel: LibraryViewModel,
    onPlayFrom: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.songs.collectAsState()
    LibraryState(
        state = state,
        ensure = viewModel::ensureSongs,
        retry = viewModel::retrySongs,
        emptyMessage = stringResource(Res.string.library_empty),
    ) { songs ->
        LazyColumn(state = rememberLazyListState(), modifier = modifier.fillMaxSize()) {
            itemsIndexed(songs) { index, song ->
                SongRow(
                    song = song,
                    onClick = { onPlayFrom(songs, index) },
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

/** The account's playlists as a cover grid, each row opening its detail page. */
@Composable
fun LibraryPlaylistsPage(
    viewModel: LibraryViewModel,
    onOpenPlaylist: (UserPlaylist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.playlists.collectAsState()
    LibraryState(
        state = state,
        ensure = viewModel::ensurePlaylists,
        retry = viewModel::retryPlaylists,
        emptyMessage = stringResource(Res.string.library_empty),
    ) { playlists ->
        // The grid is measured against the column the grid lives in, not the
        // window: the sidebar has already taken its share by the time this
        // page is drawn.
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val spec = libraryGrid(maxWidth)
            LazyVerticalGrid(
                columns = GridCells.Fixed(spec.columns),
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER + 6.dp, vertical = PAGE_TOP_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(playlists, key = { it.playlistId }) { playlist ->
                    PlaylistCard(playlist = playlist, onClick = { onOpenPlaylist(playlist) })
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(playlist: UserPlaylist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = playlist.thumbnailUrl?.artworkAt(CARD_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            playlist.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            playlist.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What this account has been playing, YouTube Music's own ordering. */
@Composable
fun RecentlyAddedPage(
    viewModel: LibraryViewModel,
    onPlayFrom: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.recents.collectAsState()
    LibraryState(
        state = state,
        ensure = viewModel::ensureRecents,
        retry = viewModel::retryRecents,
        emptyMessage = stringResource(Res.string.history_empty),
    ) { songs ->
        LazyColumn(state = rememberLazyListState(), modifier = modifier.fillMaxSize()) {
            itemsIndexed(songs) { index, song ->
                SongRow(
                    song = song,
                    onClick = { onPlayFrom(songs, index) },
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}
