package com.music.bitchord.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.browse
import com.music.bitchord.desktop.resources.downloaded_songs
import com.music.bitchord.desktop.resources.library
import com.music.bitchord.desktop.resources.listen_now
import com.music.bitchord.desktop.resources.playlists
import com.music.bitchord.desktop.resources.recently_added
import com.music.bitchord.desktop.resources.search
import com.music.bitchord.desktop.resources.search_hint
import com.music.bitchord.desktop.resources.settings
import com.music.bitchord.desktop.resources.sidebar_youtube_music
import com.music.bitchord.desktop.resources.songs
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.theme.AccentRed
import org.jetbrains.compose.resources.stringResource

/**
 * The app's navigation, the way Apple Music lays it out: a search field at
 * the top, the streaming service's own surfaces under their header, the
 * account's library under its own, then the account's playlists as plain
 * text rows.
 *
 * The icons are the chrome's own foreground — the sidebar in the reference
 * app is monochrome, and the one spot of colour belongs to the selection: a
 * red bar at the row's left edge, with the neutral fill behind it. Red icons
 * would spend the accent everywhere and mean it nowhere.
 */
@Composable
fun Sidebar(
    current: Destination,
    playlists: List<UserPlaylist>,
    mode: SidebarMode,
    onSelect: (Destination) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchFocus: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (mode == SidebarMode.RAIL) {
        RailSidebar(
            current = current,
            onSelect = onSelect,
            onSearch = onSearchFocus,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .width(SIDEBAR_WIDTH)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
    ) {
        // The search field lives here, at the sidebar's head — the reference
        // app's own placement, one keystroke away from every destination the
        // sidebar lists.
        SearchBox(
            query = query,
            onQueryChange = onQueryChange,
            onFocus = onSearchFocus,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )

        SidebarSectionHeader(stringResource(Res.string.sidebar_youtube_music))
        SidebarRow(
            icon = BitChordIcons.Home,
            label = stringResource(Res.string.listen_now),
            selected = current == Destination.Home,
        ) { onSelect(Destination.Home) }
        SidebarRow(
            icon = BitChordIcons.Explore,
            label = stringResource(Res.string.browse),
            selected = current == Destination.Explore,
        ) { onSelect(Destination.Explore) }

        SidebarSectionHeader(stringResource(Res.string.library))
        SidebarRow(
            icon = BitChordIcons.Clock,
            label = stringResource(Res.string.recently_added),
            selected = current == Destination.RecentlyAdded,
        ) { onSelect(Destination.RecentlyAdded) }
        SidebarRow(
            icon = BitChordIcons.MusicNote,
            label = stringResource(Res.string.songs),
            selected = current == Destination.LibrarySongs,
        ) { onSelect(Destination.LibrarySongs) }
        SidebarRow(
            icon = BitChordIcons.Queue,
            label = stringResource(Res.string.playlists),
            selected = current == Destination.LibraryPlaylists,
        ) { onSelect(Destination.LibraryPlaylists) }
        SidebarRow(
            icon = BitChordIcons.Download,
            label = stringResource(Res.string.downloaded_songs),
            selected = current == Destination.Downloads,
        ) { onSelect(Destination.Downloads) }

        // The account's own playlists, one row each, beneath the library's
        // "Playlists" page of them. No rows when the account has none or the
        // app cannot see them: an empty section header is a dead end, not a
        // placeholder, so the section itself is what goes away.
        if (playlists.isNotEmpty()) {
            SidebarSectionHeader(stringResource(Res.string.playlists))
            playlists.forEach { playlist ->
                val selected = current is Destination.Detail &&
                    current.browseId == playlist.browseId
                SidebarPlaylistRow(title = playlist.title, selected = selected) {
                    onSelect(
                        Destination.Detail(
                            kind = BrowseType.PLAYLIST,
                            browseId = playlist.browseId,
                            title = playlist.title,
                            subtitle = playlist.subtitle,
                            thumbnailUrl = playlist.thumbnailUrl,
                        ),
                    )
                }
            }
        }

        // Settings, last and apart: it is not a destination — it opens a
        // dialog over the shell — so it takes no section header and no
        // selection state, the way the reference app's own gear sits at the
        // foot of its sidebar.
        Spacer(Modifier.height(8.dp))
        SidebarRow(
            icon = Icons.Rounded.Settings,
            label = stringResource(Res.string.settings),
            selected = false,
        ) { onOpenSettings() }

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The collapsed sidebar: just the icons, in the same order, on the same
 * chrome — the shape the app narrows to when the window cannot afford the
 * words. Search stays reachable as an icon that opens the search page.
 */
@Composable
private fun RailSidebar(
    current: Destination,
    onSelect: (Destination) -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(SIDEBAR_RAIL_WIDTH)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        RailRow(Icons.Rounded.Search, current == Destination.Search, onClick = onSearch)
        RailRow(BitChordIcons.Home, current == Destination.Home) { onSelect(Destination.Home) }
        RailRow(BitChordIcons.Explore, current == Destination.Explore) { onSelect(Destination.Explore) }
        RailRow(BitChordIcons.Clock, current == Destination.RecentlyAdded) { onSelect(Destination.RecentlyAdded) }
        RailRow(BitChordIcons.MusicNote, current == Destination.LibrarySongs) { onSelect(Destination.LibrarySongs) }
        RailRow(BitChordIcons.Queue, current == Destination.LibraryPlaylists) { onSelect(Destination.LibraryPlaylists) }
        RailRow(Icons.Rounded.Settings, selected = false, onClick = onOpenSettings)
    }
}

@Composable
private fun RailRow(icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.outline else Color.Transparent,
        label = "railFill",
    )
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(fill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun SidebarSectionHeader(title: String) {
    Text(
        title,
        fontSize = 11.sp,
        fontWeight = FontWeight.W700,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SidebarRow(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.outline else Color.Transparent,
        label = "sidebarFill",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The selection's red edge — the one accent colour on the sidebar.
        Box(
            Modifier
                .width(3.dp)
                .height(16.dp)
                .background(
                    if (selected) AccentRed else Color.Transparent,
                    RoundedCornerShape(2.dp),
                ),
        )
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(17.dp),
        )
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.W500,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A playlist row — text only, indented to the labels above it, because the
 * sidebar's lower section lists by name and the cover lives on the page the
 * row opens.
 */
@Composable
private fun SidebarPlaylistRow(title: String, selected: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.outline else Color.Transparent,
        label = "playlistFill",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(start = 35.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The sidebar's search field — the desktop's search entry, at the top of the
 * navigation where the reference app keeps it. Typing here and typing in the
 * search page's own field drive the same query state, so the two never
 * disagree; focusing this one is what moves the app to the search page.
 */
@Composable
private fun SearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    // The well the field sits in. On a light chrome the page's own white is
    // the inset; on a dark chrome pure black would punch a hole in the
    // sidebar rather than dent it — a well is a step down from the surface
    // around it, not a void — so the dark side takes the chrome's own tone
    // dimmed a step instead.
    val chrome = MaterialTheme.colorScheme.surfaceVariant
    val fieldFill = if (chrome.luminance() < 0.5f) {
        Color.Black.copy(alpha = 0.30f)
    } else {
        MaterialTheme.colorScheme.background
    }
    // The field on the chrome wants a selection colour that reads against the
    // page's own background colour it sits on, so the default is overridden
    // for this one field.
    val selectionColors = TextSelectionColors(
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        handleColor = MaterialTheme.colorScheme.primary,
    )
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onBackground,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .background(fieldFill, shape)
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = stringResource(Res.string.search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(Res.string.search_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                }
            },
            modifier = modifier
                .onFocusChanged { if (it.isFocused) onFocus() },
        )
    }
}
