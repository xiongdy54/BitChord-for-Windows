package com.music.bitchord.ui.shell

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.Song
import com.music.bitchord.desktop.playback.PlayerController
import com.music.bitchord.ui.SearchViewModel
import com.music.bitchord.ui.components.topBarContentPadding
import com.music.bitchord.ui.screens.SearchScreen

/**
 * Search, wired the way MainActivity wires it, with the same rule the home
 * feed learned: a row that names a collection asks it what it holds and plays
 * the first of it, because the page that would otherwise open is a later slice.
 *
 * What a tap records in history is the entity that was tapped — a track carries
 * its videoId, a collection its browseId — so the recents list can render real
 * cover art and go straight back to what was picked rather than re-running the
 * text.
 *
 * The gestures the action sheets own (long-press, swipe) do nothing yet; those
 * sheets are their own slice.
 */
@Composable
fun SearchPage(
    vm: SearchViewModel,
    player: PlayerController,
    initialQuery: String = "",
    autoPlayFirst: Boolean = false,
) {
    val query by vm.query.collectAsState()
    val filter by vm.filter.collectAsState()
    val results by vm.results.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val typeaheadResults by vm.typeaheadResults.collectAsState()
    val history by vm.history.collectAsState()
    val scrollResetTrigger by vm.scrollResetTrigger.collectAsState()
    // Read for two things only: the content padding that clears the mini player, and
    // the autoplay debug hook's "something is already playing, stop". Slice 1 had a
    // `current` flow of its own for these; it was a copy of this snapshot's `song`.
    val snapshot by player.state.collectAsState()
    val listState = rememberLazyListState()
    var focusRequested by remember { mutableStateOf(false) }

    // The tab was just opened — put the caret in the field, the way tapping
    // the search tab does upstream.
    LaunchedEffect(Unit) { focusRequested = true }

    fun playSong(target: Song) {
        player.play(target)
        vm.record(
            SearchHistoryEntity(
                id = target.videoId,
                title = target.title,
                subtitle = target.artist,
                artworkUrl = target.thumbnailUrl,
                entityType = EntityType.TRACK,
            ),
        )
    }

    fun openCollection(item: BrowseItem) {
        player.playCollection(item.browseId, item.title)
        vm.record(
            SearchHistoryEntity(
                id = item.browseId,
                title = item.title,
                subtitle = item.subtitle,
                artworkUrl = item.thumbnailUrl,
                entityType = when (item.type) {
                    BrowseType.ALBUM -> EntityType.ALBUM
                    BrowseType.ARTIST -> EntityType.ARTIST
                    else -> EntityType.PLAYLIST
                },
            ),
        )
    }

    // Debug hook, the same shape the home page has: drives a query and the
    // first row's own handler so the screenshot test exercises the real path.
    var hookFired by remember { mutableStateOf(false) }
    LaunchedEffect(initialQuery, autoPlayFirst, results) {
        if (initialQuery.isNotBlank() && !hookFired) {
            hookFired = true
            vm.onSubmitText(initialQuery)
        }
        if (!autoPlayFirst || results == null || snapshot.song != null) return@LaunchedEffect
        val firstSong = (results as? com.music.bitchord.data.model.UiState.Success)?.data
            ?.firstNotNullOfOrNull { row ->
                when (row) {
                    is com.music.bitchord.data.model.SearchResult.TopTrack -> row.song
                    is com.music.bitchord.data.model.SearchResult.Track -> row.song
                    is com.music.bitchord.data.model.SearchResult.Browse -> null
                }
            }
            ?: return@LaunchedEffect
        playSong(firstSong)
    }

    SearchScreen(
        query = query,
        onQueryChange = vm::onQueryChange,
        filter = filter,
        onFilterChange = vm::onFilterChange,
        results = results,
        loadingMore = loadingMore,
        onLoadMore = vm::loadMore,
        listState = listState,
        scrollResetTrigger = scrollResetTrigger,
        focusRequested = focusRequested,
        onFocusHandled = { focusRequested = false },
        onSongClick = { songs, index -> songs.getOrNull(index)?.let(::playSong) },
        onSongLongPress = {},
        onSongSwipe = {},
        onTopResultPlay = ::playSong,
        onTopResultPlaylist = ::playSong,
        onBrowseClick = ::openCollection,
        history = history,
        suggestions = suggestions,
        typeaheadResults = typeaheadResults,
        onSubmit = vm::submit,
        onSuggestionClick = vm::onSubmitText,
        onHistoryClick = { entity ->
            if (entity.entityType == EntityType.TRACK) {
                playSong(
                    Song(
                        videoId = entity.id,
                        title = entity.title,
                        artist = entity.subtitle,
                        thumbnailUrl = entity.artworkUrl,
                    ),
                )
            } else {
                player.playCollection(entity.id, entity.title)
            }
        },
        onHistoryRemove = vm::removeHistory,
        onHistoryClear = vm::clearHistory,
        contentPadding = PaddingValues(
            top = topBarContentPadding(),
            bottom = if (snapshot.song != null) 210.dp else 140.dp,
        ),
    )
}
