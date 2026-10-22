package com.music.bitchord.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.isSameTrackAs
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.album_count
import com.music.bitchord.desktop.resources.albums
import com.music.bitchord.desktop.resources.artist_count
import com.music.bitchord.desktop.resources.artists
import com.music.bitchord.desktop.resources.back
import com.music.bitchord.desktop.resources.cancel
import com.music.bitchord.desktop.resources.clear_search
import com.music.bitchord.desktop.resources.delete
import com.music.bitchord.desktop.resources.local_music
import com.music.bitchord.desktop.resources.local_music_delete_confirm
import com.music.bitchord.desktop.resources.local_music_no_folders
import com.music.bitchord.desktop.resources.no_local_albums
import com.music.bitchord.desktop.resources.now_playing
import com.music.bitchord.desktop.resources.play
import com.music.bitchord.desktop.resources.search_this_folder
import com.music.bitchord.desktop.resources.select_all
import com.music.bitchord.desktop.resources.selected
import com.music.bitchord.desktop.resources.settings
import com.music.bitchord.desktop.resources.songs
import com.music.bitchord.desktop.resources.sort_date_added
import com.music.bitchord.desktop.resources.sort_date_modified
import com.music.bitchord.desktop.resources.sort_music
import com.music.bitchord.desktop.resources.sort_title_ascending
import com.music.bitchord.desktop.resources.sort_title_descending
import com.music.bitchord.desktop.resources.song_count_plural
import com.music.bitchord.desktop.resources.switch_to_grid_view
import com.music.bitchord.desktop.resources.switch_to_list_view
import com.music.bitchord.desktop.resources.unknown_artist
import com.music.bitchord.desktop.resources.upload_to_webdav
import com.music.bitchord.desktop.resources.webdav
import com.music.bitchord.desktop.resources.webdav_not_configured
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.rememberRemoteArtworkUrl
import com.music.bitchord.ui.icons.BitChordIcons
import java.io.File
import java.util.Locale
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private const val LOCAL_TAB_SONGS = 0
private const val LOCAL_TAB_ARTISTS = 1
private const val LOCAL_TAB_ALBUMS = 2

/** Where the page's list comes from — the two personal libraries the desktop carries. */
private enum class LocalSource(val browseId: String) {
    /** The folders the user chose, scanned on page entry. */
    DEVICE("local:all"),

    /** The configured WebDAV server, listed on page entry. */
    WEBDAV(WebDavConfig.BROWSE_ID),
}

/**
 * Local Music, the desktop form of upstream's `LocalMusicScreen`: three tabs,
 * list and grid views, live search, the sort menu, and the artist/album
 * drill-down — plus the one thing the desktop needs that the phone's single
 * entry point never had to choose: which personal library is showing. Upstream
 * routes `local:all` and `local:webdav` to this one page; the desktop sidebar
 * has one row for both, so the page carries a two-way source toggle, and each
 * source scans (or lists) on entry — exactly the no-persistence shape
 * upstream's scan keeps.
 *
 * Selection is desktop-native where upstream long-pressed: Ctrl+click toggles
 * a row, Shift+click takes the range from the last anchor, and once a selection
 * exists a plain click toggles too — the way file managers behave, and the way
 * the page stops being a player for a moment. The selection bar offers
 * upload-to-WebDAV and delete-with-confirm; both are local-only acts (a
 * server-side row is not a file of this machine's to delete, and uploading it
 * would send it back where it came from).
 */
@Composable
fun LocalMusicPage(
    onPlayFrom: (List<Song>, Int, String) -> Unit,
    currentSong: Song?,
    isPlaying: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var source by remember { mutableStateOf(LocalSource.DEVICE) }
    var selectedTab by remember { mutableIntStateOf(LOCAL_TAB_SONGS) }
    var searchQuery by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var songs by remember { mutableStateOf<List<Song>?>(null) }

    val sortOrder by AppSettings.localMusicSort.collectAsState()
    val viewType by AppSettings.localMusicViewType.collectAsState()
    val webdavUrl by AppSettings.webdavUrl.collectAsState()
    val localFolders by AppSettings.localMusicFolders.collectAsState()

    LaunchedEffect(source, refresh, webdavUrl) {
        songs = when (source) {
            LocalSource.DEVICE -> LocalMediaRepository.getLocalMusic()
            LocalSource.WEBDAV -> if (WebDavRepository.isConfigured()) WebDavRepository.getSongs() else emptyList()
        }
    }

    val sortedSongs = remember(songs, sortOrder) { songs.orEmpty().sortedForLibrary(sortOrder) }

    // Selection state, cleared with the source it was made in: ids from one
    // library mean nothing in the other.
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectionAnchor by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(source) {
        selectedIds = emptySet()
        selectionAnchor = null
    }
    val selecting = selectedIds.isNotEmpty() && source == LocalSource.DEVICE

    fun selectionClick(list: List<Song>, index: Int, ctrl: Boolean, shift: Boolean) {
        val song = list.getOrNull(index) ?: return
        when {
            shift && selectionAnchor != null -> {
                val from = minOf(selectionAnchor!!, index)
                val to = maxOf(selectionAnchor!!, index)
                selectedIds = selectedIds + list.subList(from, to + 1).map { it.videoId }.toSet()
            }
            else -> {
                selectionAnchor = index
                selectedIds = if (song.videoId in selectedIds) {
                    selectedIds - song.videoId
                } else {
                    selectedIds + song.videoId
                }
            }
        }
    }

    // When non-null, a drill-down list for that artist or album is showing.
    var drillDownLabel by remember { mutableStateOf<String?>(null) }
    var drillDownSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    val inDrillDown = drillDownLabel != null
    val leaveDrillDown = {
        drillDownLabel = null
        drillDownSongs = emptyList()
    }

    // The page-level acts, hoisted above the tree that arms them: the selection
    // bar's buttons write these, the dialogs below read them.
    var uploadChoice by remember { mutableStateOf<List<Song>?>(null) }
    var deleteChoice by remember { mutableStateOf<List<Song>?>(null) }

    val emptyMessage = when {
        source == LocalSource.DEVICE && localFolders.isEmpty() ->
            stringResource(Res.string.local_music_no_folders)
        source == LocalSource.WEBDAV && webdavUrl.isBlank() ->
            stringResource(Res.string.webdav_not_configured)
        else -> null
    }

    Column(modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(
                count = selectedIds.size,
                allSelected = sortedSongs.isNotEmpty() && selectedIds.containsAll(sortedSongs.map { it.videoId }),
                onSelectAll = {
                    selectionAnchor = null
                    selectedIds = sortedSongs.mapTo(linkedSetOf()) { it.videoId }
                },
                onUpload = {
                    uploadChoice = sortedSongs.filter { it.videoId in selectedIds }
                    selectedIds = emptySet()
                    selectionAnchor = null
                },
                onDelete = {
                    deleteChoice = sortedSongs.filter { it.videoId in selectedIds }
                    selectedIds = emptySet()
                    selectionAnchor = null
                },
                onCancel = {
                    selectedIds = emptySet()
                    selectionAnchor = null
                },
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
            )
        } else {
            SourceSearchRow(
                source = source,
                onSourceChange = {
                    source = it
                    leaveDrillDown()
                    searchQuery = ""
                },
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                sortOrder = sortOrder,
                onSortOrderChange = { AppSettings.setLocalMusicSort(it) },
                viewType = viewType,
                onViewTypeToggle = {
                    AppSettings.setLocalMusicViewType(
                        if (viewType == LibraryViewType.GRID) LibraryViewType.LIST else LibraryViewType.GRID,
                    )
                },
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
            )
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            LocalTab(Icons.Rounded.MusicNote, stringResource(Res.string.songs), selectedTab == LOCAL_TAB_SONGS) {
                selectedTab = LOCAL_TAB_SONGS
                leaveDrillDown()
            }
            LocalTab(Icons.Rounded.Person, stringResource(Res.string.artists), selectedTab == LOCAL_TAB_ARTISTS) {
                selectedTab = LOCAL_TAB_ARTISTS
                leaveDrillDown()
            }
            LocalTab(Icons.Rounded.Album, stringResource(Res.string.albums), selectedTab == LOCAL_TAB_ALBUMS) {
                selectedTab = LOCAL_TAB_ALBUMS
                leaveDrillDown()
            }
        }

        AnimatedContent(
            targetState = Triple(if (inDrillDown) "drill:$drillDownLabel" else "tab:$selectedTab", viewType, source),
            transitionSpec = {
                if (targetState.first.startsWith("drill:")) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it } + fadeOut())
                }
            },
            label = "local_music_content",
            modifier = Modifier.fillMaxSize(),
        ) { (key, view, src) ->
            when {
                sortedSongs.isEmpty() && emptyMessage != null -> Box(Modifier.fillMaxSize()) {
                    MessageState(
                        message = emptyMessage,
                        actionLabel = if (src == LocalSource.DEVICE && localFolders.isEmpty()) {
                            stringResource(Res.string.settings)
                        } else {
                            null
                        },
                        onAction = if (src == LocalSource.DEVICE && localFolders.isEmpty()) onOpenSettings else null,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                key.startsWith("drill:") -> DrillDownSongList(
                    label = drillDownLabel ?: "",
                    songs = drillDownSongs,
                    viewType = view,
                    selectedIds = selectedIds,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onSongClick = { tracks, index ->
                        if (selectedIds.isNotEmpty()) selectionClick(tracks, index, ctrl = false, shift = false)
                        else onPlayFrom(tracks, index, src.browseId)
                    },
                    onSelectionClick = { list, index, ctrl, shift -> selectionClick(list, index, ctrl, shift) },
                    onBack = leaveDrillDown,
                )

                key == "tab:$LOCAL_TAB_SONGS" -> {
                    val filteredSongs = remember(sortedSongs, searchQuery) {
                        if (searchQuery.isBlank()) sortedSongs
                        else sortedSongs.filter { it.matchesSearch(searchQuery) }
                    }
                    SongsTab(
                        songs = filteredSongs,
                        viewType = view,
                        selectedIds = selectedIds,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        onSongClick = { tracks, index ->
                            if (selectedIds.isNotEmpty()) selectionClick(tracks, index, ctrl = false, shift = false)
                            else onPlayFrom(tracks, index, src.browseId)
                        },
                        onSelectionClick = { list, index, ctrl, shift -> selectionClick(list, index, ctrl, shift) },
                    )
                }

                key == "tab:$LOCAL_TAB_ARTISTS" -> {
                    val artists = remember(sortedSongs, searchQuery) {
                        sortedSongs.groupBy { it.artist }
                            .entries
                            .filter { searchQuery.isBlank() || it.key.contains(searchQuery, ignoreCase = true) }
                            .sortedBy { it.key.lowercase(Locale.ROOT) }
                    }
                    ArtistsTab(
                        artists = artists,
                        viewType = view,
                        onArtistClick = { artist, artistSongs ->
                            drillDownLabel = artist
                            drillDownSongs = artistSongs
                        },
                    )
                }

                else -> {
                    val albums = remember(sortedSongs, searchQuery) {
                        sortedSongs.groupBy { it.albumName }
                            .mapNotNull { (name, group) -> name?.let { it to group } }
                            .filter { (name, _) -> searchQuery.isBlank() || name.contains(searchQuery, ignoreCase = true) }
                            .sortedBy { (name, _) -> name.lowercase(Locale.ROOT) }
                    }
                    AlbumsTab(
                        albums = albums,
                        viewType = view,
                        onAlbumClick = { title, albumSongs ->
                            drillDownLabel = title
                            drillDownSongs = albumSongs
                        },
                    )
                }
            }
        }
    }

    // ---- page-level acts ------------------------------------------------------------

    uploadChoice?.let { chosen ->
        WebDavUploadDialog(
            songs = chosen,
            onDismiss = { uploadChoice = null },
            onFinished = { if (source == LocalSource.WEBDAV) refresh++ },
        )
    }
    deleteChoice?.let { chosen ->
        DeleteFilesDialog(
            count = chosen.size,
            onConfirm = {
                chosen.forEach { song ->
                    song.localUri?.let { path -> runCatching { File(path).delete() } }
                }
                deleteChoice = null
                refresh++
            },
            onDismiss = { deleteChoice = null },
        )
    }
}

/** Whether this track is a hit for a query typed into the header's search field. */
private fun Song.matchesSearch(query: String): Boolean =
    title.contains(query, ignoreCase = true) ||
        artist.contains(query, ignoreCase = true) ||
        albumName?.contains(query, ignoreCase = true) == true

private fun List<Song>.sortedForLibrary(order: LocalMusicSort): List<Song> =
    with(LocalMediaRepository) { sortedForLibrary(order) }

// ── selection gestures ──────────────────────────────────────────────────────────

/**
 * The desktop's selection gestures, wrapped around a row or card.
 *
 * The handler rides the *initial* pointer pass, which sees every event before
 * the clickable inside does: a Ctrl/Shift/armed click is consumed here so the
 * row's own click never fires, and a plain click while nothing is selected is
 * passed through untouched, so playing stays a one-act affair. Dragging breaks
 * the gesture without consuming it — scrolling the list and swiping a row are
 * the clickables' business, not this one's.
 *
 * [list] is the visible list the range-select is taken over, read at event
 * time so it tracks the search filter without restarting the handler.
 */
@Composable
private fun SelectionGestures(
    index: Int,
    list: List<Song>,
    armed: Boolean,
    onSelectionClick: (List<Song>, Int, ctrl: Boolean, shift: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val handler by rememberUpdatedState(onSelectionClick)
    val visibleList by rememberUpdatedState(list)
    val interceptPlain by rememberUpdatedState(armed)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(index) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val ctrl = currentEvent.keyboardModifiers.isCtrlPressed
                    val shift = currentEvent.keyboardModifiers.isShiftPressed
                    var up: PointerInputChange? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: break
                        // A scroll or the row's own swipe took the gesture over.
                        if (change.isConsumed) break
                        if (change.changedToUpIgnoreConsumed()) {
                            up = change
                            break
                        }
                        if (change.positionChangeIgnoreConsumed() != Offset.Zero) break
                    }
                    val landed = up ?: return@awaitEachGesture
                    if (ctrl || shift || interceptPlain) {
                        landed.consume()
                        handler(visibleList, index, ctrl, shift)
                    }
                }
            },
    ) {
        content()
    }
}

// ── header: source, search, view, sort ──────────────────────────────────────────

@Composable
private fun SourceSearchRow(
    source: LocalSource,
    onSourceChange: (LocalSource) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    sortOrder: LocalMusicSort,
    onSortOrderChange: (LocalMusicSort) -> Unit,
    viewType: LibraryViewType,
    onViewTypeToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceChip(
                label = stringResource(Res.string.local_music),
                selected = source == LocalSource.DEVICE,
                onClick = { onSourceChange(LocalSource.DEVICE) },
            )
            SourceChip(
                label = stringResource(Res.string.webdav),
                selected = source == LocalSource.WEBDAV,
                onClick = { onSourceChange(LocalSource.WEBDAV) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.search_this_folder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.clear_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onViewTypeToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (viewType == LibraryViewType.GRID) BitChordIcons.ListView else BitChordIcons.GridView,
                    contentDescription = stringResource(
                        if (viewType == LibraryViewType.GRID) {
                            Res.string.switch_to_list_view
                        } else {
                            Res.string.switch_to_grid_view
                        },
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
            Box {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { sortMenuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Sort,
                        contentDescription = stringResource(Res.string.sort_music),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false },
                ) {
                    LocalMusicSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.localizedLabel()) },
                            trailingIcon = if (option == sortOrder) {
                                {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = stringResource(Res.string.selected),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                onSortOrderChange(option)
                                sortMenuOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The two personal libraries, as the page's own wayfinding. */
@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun LocalMusicSort.localizedLabel(): String = when (this) {
    LocalMusicSort.TITLE_ASC -> stringResource(Res.string.sort_title_ascending)
    LocalMusicSort.TITLE_DESC -> stringResource(Res.string.sort_title_descending)
    LocalMusicSort.DATE_ADDED -> stringResource(Res.string.sort_date_added)
    LocalMusicSort.DATE_MODIFIED -> stringResource(Res.string.sort_date_modified)
}

/** The selection mode's replacement for the search row — upstream's bar, desktop-drawn. */
@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onUpload: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Check,
            contentDescription = stringResource(Res.string.select_all),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(enabled = !allSelected, onClick = onSelectAll)
                .padding(8.dp),
        )
        Text(
            text = "$count ${stringResource(Res.string.selected)}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUpload, enabled = count > 0) {
            Icon(
                Icons.Rounded.FileUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(Res.string.upload_to_webdav), color = MaterialTheme.colorScheme.primary)
        }
        TextButton(onClick = onDelete, enabled = count > 0) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel)) }
    }
}

// ── tabs ────────────────────────────────────────────────────────────────────────

@Composable
private fun SongsTab(
    songs: List<Song>,
    viewType: LibraryViewType,
    selectedIds: Set<String>,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSelectionClick: (List<Song>, Int, ctrl: Boolean, shift: Boolean) -> Unit,
) {
    if (viewType == LibraryViewType.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            state = rememberLazyGridState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 6.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(
                    icon = Icons.Rounded.LibraryMusic,
                    title = pluralStringResource(Res.plurals.song_count_plural, songs.size, songs.size),
                )
            }
            itemsIndexed(songs, key = { index, song -> "${song.videoId}_$index" }) { index, song ->
                SelectionGestures(
                    index = index,
                    list = songs,
                    armed = selectedIds.isNotEmpty(),
                    onSelectionClick = onSelectionClick,
                ) {
                    SongGridCard(
                        song = song,
                        selected = song.videoId in selectedIds,
                        isCurrent = song.isSameTrackAs(currentSong),
                        onClick = { onSongClick(songs, index) },
                    )
                }
            }
        }
    } else {
        LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize()) {
            item {
                SectionHeader(
                    icon = Icons.Rounded.LibraryMusic,
                    title = pluralStringResource(Res.plurals.song_count_plural, songs.size, songs.size),
                )
            }
            itemsIndexed(songs, key = { index, song -> "${song.videoId}_$index" }) { index, song ->
                SelectionGestures(
                    index = index,
                    list = songs,
                    armed = selectedIds.isNotEmpty(),
                    onSelectionClick = onSelectionClick,
                ) {
                    SongRow(
                        song = song,
                        selected = song.videoId in selectedIds,
                        isCurrent = song.isSameTrackAs(currentSong),
                        isPlaying = song.isSameTrackAs(currentSong) && isPlaying,
                        onClick = { onSongClick(songs, index) },
                    )
                }
                if (index < songs.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun ArtistsTab(
    artists: List<Map.Entry<String, List<Song>>>,
    viewType: LibraryViewType,
    onArtistClick: (String, List<Song>) -> Unit,
) {
    CollectionListOrGrid(
        count = artists.size,
        headerIcon = Icons.Rounded.Person,
        headerTitle = pluralStringResource(Res.plurals.artist_count, artists.size, artists.size),
        viewType = viewType,
        emptyMessage = null,
        rows = artists,
        key = { it.key },
        title = { it.key },
        subtitle = { pluralStringResource(Res.plurals.song_count_plural, it.value.size, it.value.size) },
        thumbnailUrl = { it.value.firstNotNullOfOrNull { song -> song.thumbnailUrl } },
        placeholder = Icons.Rounded.Person,
        circle = true,
        onClick = { entry -> onArtistClick(entry.key, entry.value) },
        // An artist has no art of its own on disk — the first track's
        // embedded cover stands for it, resolved through the same seam the
        // song rows use, so a local artist lights up like a remote one.
        artSong = { it.value.firstOrNull() },
    )
}

@Composable
private fun AlbumsTab(
    albums: List<Pair<String, List<Song>>>,
    viewType: LibraryViewType,
    onAlbumClick: (String, List<Song>) -> Unit,
) {
    CollectionListOrGrid(
        count = albums.size,
        headerIcon = Icons.Rounded.Album,
        headerTitle = pluralStringResource(Res.plurals.album_count, albums.size, albums.size),
        viewType = viewType,
        emptyMessage = stringResource(Res.string.no_local_albums),
        rows = albums,
        key = { it.first },
        title = { it.first },
        subtitle = { (album, albumSongs) ->
            buildString {
                albumSongs.firstOrNull()?.artist?.takeIf { it.isNotBlank() && it != album }
                    ?.let { append("$it · ") }
                append(pluralStringResource(Res.plurals.song_count_plural, albumSongs.size, albumSongs.size))
            }
        },
        thumbnailUrl = { it.second.firstNotNullOfOrNull { song -> song.thumbnailUrl } },
        placeholder = Icons.Rounded.Album,
        circle = false,
        onClick = { entry -> onAlbumClick(entry.first, entry.second) },
        // The album's cover, out of its own first track — same seam, same
        // reason as the artist's.
        artSong = { it.second.firstOrNull() },
    )
}

/**
 * The Artists/Albums tabs are one shape drawn twice — a titled collection with
 * a count and a stand-in glyph where no cover exists — so they share this one
 * body the way upstream's two tabs share their layout rather than their code.
 */
@Composable
private fun <T> CollectionListOrGrid(
    count: Int,
    headerIcon: ImageVector,
    headerTitle: String,
    viewType: LibraryViewType,
    emptyMessage: String?,
    rows: List<T>,
    key: (T) -> String,
    title: (T) -> String,
    subtitle: @Composable (T) -> String,
    thumbnailUrl: (T) -> String?,
    placeholder: ImageVector,
    circle: Boolean,
    onClick: (T) -> Unit,
    /** A track whose embedded art stands for the collection, where there is one. */
    artSong: (T) -> Song?,
) {
    if (rows.isEmpty()) {
        if (emptyMessage != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MessageState(message = emptyMessage)
            }
        }
        return
    }
    if (viewType == LibraryViewType.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 130.dp),
            state = rememberLazyGridState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 6.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(icon = headerIcon, title = headerTitle)
            }
            items(rows, key = key) { entry ->
                CollectionGridCard(
                    title = title(entry),
                    subtitle = subtitle(entry),
                    shape = if (circle) CircleShape else RoundedCornerShape(12.dp),
                    thumbnailUrl = thumbnailUrl(entry),
                    placeholder = placeholder,
                    onClick = { onClick(entry) },
                    artSong = artSong(entry),
                )
            }
        }
    } else {
        LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize()) {
            item { SectionHeader(icon = headerIcon, title = headerTitle) }
            items(rows.size, key = { key(rows[it]) }) { i ->
                val entry = rows[i]
                CollectionRow(
                    title = title(entry),
                    subtitle = subtitle(entry),
                    thumbnailUrl = thumbnailUrl(entry),
                    placeholder = placeholder,
                    circle = circle,
                    onClick = { onClick(entry) },
                    artSong = artSong(entry),
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ── rows and cards ──────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SongGridCard(
    song: Song,
    selected: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        val shape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
            val art = rememberRemoteArtworkUrl(song)
            if (art != null) {
                AsyncImage(
                    model = art.artworkAt(CARD_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (isCurrent) {
                Icon(
                    Icons.Rounded.GraphicEq,
                    contentDescription = stringResource(Res.string.now_playing),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song.artist.ifBlank { stringResource(Res.string.unknown_artist) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CollectionRow(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    placeholder: ImageVector,
    circle: Boolean,
    onClick: () -> Unit,
    /** A track whose embedded art stands for the collection, where there is one. */
    artSong: Song? = null,
) {
    val shape = if (circle) CircleShape else RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CollectionArtwork(url = thumbnailUrl, placeholder = placeholder, shape = shape, size = 48.dp, artSong = artSong)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun CollectionGridCard(
    title: String,
    subtitle: String,
    shape: Shape,
    thumbnailUrl: String?,
    placeholder: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** A track whose embedded art stands for the collection, where there is one. */
    artSong: Song? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .aspectRatio(1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = placeholder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(44.dp),
            )
            // The collection's own cover: its own thumbnail where it has one
            // (a remote artist's), else the embedded art of the track that
            // stands for it — the same seam the song rows resolve through.
            val art = rememberRemoteArtworkUrl(artSong) ?: thumbnailUrl
            if (art != null) {
                AsyncImage(
                    model = art.artworkAt(CARD_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CollectionArtwork(
    url: String?,
    placeholder: ImageVector,
    shape: Shape,
    size: Dp,
    /** A track whose embedded art stands for the collection, where there is one. */
    artSong: Song? = null,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = placeholder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(size * 0.54f),
        )
        // Same resolution as the grid card's: the row's own thumbnail, else
        // the embedded art of the track standing for it.
        val art = rememberRemoteArtworkUrl(artSong) ?: url
        if (art != null) {
            AsyncImage(
                model = art.artworkAt(ROW_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(shape),
            )
        }
    }
}

// ── drill-down ──────────────────────────────────────────────────────────────────

/**
 * The artist/album drill-down, inline the way upstream's is: a back glyph, a
 * play-all button, and the collection's rows. Upstream's shuffle button is
 * not carried — on the desktop shuffle is a toolbar transport toggle
 * ([QueueShuffle]) rather than a per-page act.
 */
@Composable
private fun DrillDownSongList(
    label: String,
    songs: List<Song>,
    viewType: LibraryViewType,
    selectedIds: Set<String>,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSelectionClick: (List<Song>, Int, ctrl: Boolean, shift: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = PAGE_GUTTER, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(Res.string.back),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        item {
            Box(Modifier.padding(horizontal = PAGE_GUTTER, vertical = 10.dp)) {
                Button(onClick = { if (songs.isNotEmpty()) onSongClick(songs, 0) }) {
                    Icon(BitChordIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.play))
                }
            }
        }
        itemsIndexed(songs, key = { index, song -> "${song.videoId}_$index" }) { index, song ->
            SelectionGestures(
                index = index,
                list = songs,
                armed = selectedIds.isNotEmpty(),
                onSelectionClick = onSelectionClick,
            ) {
                SongRow(
                    song = song,
                    selected = song.videoId in selectedIds,
                    isCurrent = song.isSameTrackAs(currentSong),
                    isPlaying = song.isSameTrackAs(currentSong) && isPlaying,
                    trackNumber = index + 1,
                    onClick = { onSongClick(songs, index) },
                )
            }
            if (index < songs.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun LocalTab(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        selectedContentColor = MaterialTheme.colorScheme.primary,
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The delete confirmation. Upstream's tap-again pattern does not travel —
 * the desktop's danger verb is a dialog — so this is the same question the
 * upstream string asks, with the file count where the upstream string's
 * quoted title goes. It deletes the files from disk; the Downloads record,
 * where one exists, is pruned by its own verification on the next read.
 */
@Composable
private fun DeleteFilesDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.width(360.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(Res.string.local_music_delete_confirm, count),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
                    Button(onClick = onConfirm) { Text(stringResource(Res.string.delete)) }
                }
            }
        }
    }
}
