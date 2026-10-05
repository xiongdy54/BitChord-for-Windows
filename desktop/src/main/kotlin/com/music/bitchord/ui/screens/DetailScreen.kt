package com.music.bitchord.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.album
import com.music.bitchord.desktop.resources.artist
import com.music.bitchord.desktop.resources.couldnt_load_tracks
import com.music.bitchord.desktop.resources.ic_player_play
import com.music.bitchord.desktop.resources.monthly_listeners
import com.music.bitchord.desktop.resources.no_tracks_here
import com.music.bitchord.desktop.resources.play
import com.music.bitchord.desktop.resources.playlist
import com.music.bitchord.desktop.resources.retry
import com.music.bitchord.desktop.resources.shuffle
import com.music.bitchord.desktop.resources.suggested
import com.music.bitchord.ui.DetailUi
import com.music.bitchord.ui.DetailViewModel
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.theme.AccentRed
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The album / playlist / artist page, drawn the way Apple Music draws one: a
 * full-bleed wash of the cover's own colours behind a centred sleeve, a kind
 * label over a big title, the two play pills, then the track list.
 *
 * Upstream's DetailScreen is the same three pages in one file, and this is the
 * same idea at desktop size — the header is centred rather than flush because
 * there is room for it, and the track rows are the app's own [SongRow] in its
 * numbered variant, which is what the album listing there draws too. The
 * layout is desktop-native; the content is the fetched page's, verbatim.
 */
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    listState: LazyListState = rememberLazyListState(),
    onPlayFrom: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onShelfItemClick: (ShelfItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    when (val value = state) {
        is UiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is UiState.Error -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(Res.string.couldnt_load_tracks),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(Res.string.retry),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { viewModel.retry() }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }

        is UiState.Success -> DetailContent(
            ui = value.data,
            viewModel = viewModel,
            listState = listState,
            onPlayFrom = onPlayFrom,
            onShufflePlay = onShufflePlay,
            onShelfItemClick = onShelfItemClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun DetailContent(
    ui: DetailUi,
    viewModel: DetailViewModel,
    listState: LazyListState,
    onPlayFrom: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onShelfItemClick: (ShelfItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Walk to the end of a paginated listing without a "show more" button:
    // four rows from the bottom the next page is already on its way, which is
    // how the home feed behaves and how a scrolled list expects to behave.
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            last.index >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        item(key = "header") {
            DetailHeader(
                ui = ui,
                kind = viewModel.kind,
                onPlay = { if (ui.songs.isNotEmpty()) onPlayFrom(ui.songs, 0) },
                onShuffle = { if (ui.songs.isNotEmpty()) onShufflePlay(ui.songs) },
            )
        }

        if (ui.songs.isEmpty() && ui.sections.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(Res.string.no_tracks_here),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }

        itemsIndexed(ui.songs) { index, song ->
            SongRow(
                song = song,
                onClick = { onPlayFrom(ui.songs, index) },
                trackNumber = index + 1,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            )
        }

        if (ui.suggested.isNotEmpty()) {
            item(key = "suggested-header") {
                SectionHeader(stringResource(Res.string.suggested))
            }
            itemsIndexed(ui.suggested) { index, song ->
                SongRow(
                    song = song,
                    onClick = { onPlayFrom(ui.suggested, index) },
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                )
            }
        }

        // An artist page's body: the albums, singles and features the page
        // ships as shelves, drawn by the same carousel the home feed uses.
        ui.sections.forEach { shelf ->
            item(key = "shelf-${shelf.title}") {
                Box(Modifier.padding(top = 10.dp)) {
                    Shelf(shelf = shelf, onItemClick = onShelfItemClick)
                }
            }
        }

        item(key = "footer") { Spacer(Modifier.height(80.dp)) }
    }
}

/**
 * The page's top: the blurred cover wash, the sleeve, the kind label, the
 * title, the listeners line, and the two pills. The wash fades into the page
 * background rather than carrying the whole column, so a long track list ends
 * on solid ground the way Apple Music's does.
 */
@Composable
private fun DetailHeader(
    ui: DetailUi,
    kind: BrowseType,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        // The wash: the cover itself, blurred past recognition, pulled back
        // to the page colour early enough that the title reads on solid
        // ground — Apple Music's wash ends behind the sleeve, not behind the
        // words, and a half-faded title is the difference.
        AsyncImage(
            model = ui.thumbnailUrl?.artworkAt(CARD_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .blur(80.dp),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.35f to MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                        0.55f to MaterialTheme.colorScheme.background,
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AsyncImage(
                model = ui.thumbnailUrl?.artworkAt(CARD_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(190.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                kindLabel(kind),
                fontSize = 11.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                ui.title,
                style = MaterialTheme.typography.headlineLarge,
                // Explicit: the header draws on the page background, not
                // inside a Surface, so the content-colour default is black —
                // a black title on a black page, invisible.
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val listeners = ui.monthlyListenersText?.let {
                stringResource(Res.string.monthly_listeners, it)
            }
            val subtitle = listeners ?: ui.subtitle
            subtitle?.let {
                Spacer(Modifier.height(5.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailPill(
                    label = stringResource(Res.string.play),
                    icon = {
                        Icon(
                            painterResource(Res.drawable.ic_player_play),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(11.dp),
                        )
                    },
                    filled = true,
                    onClick = onPlay,
                )
                DetailPill(
                    label = stringResource(Res.string.shuffle),
                    icon = {
                        Icon(
                            BitChordIcons.Shuffle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(13.dp),
                        )
                    },
                    filled = false,
                    onClick = onShuffle,
                )
            }
        }
    }
}

/** Apple Music's two header pills: one red and filled, one in the page's own colours. */
@Composable
private fun DetailPill(
    label: String,
    icon: @Composable () -> Unit,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val fill = if (filled) {
        AccentRed
    } else {
        // A translucent wash rather than the solid variant colour: the pill
        // sits over the cover wash, and a solid chip would cut a hole in it.
        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)
    }
    Row(
        modifier = Modifier
            .clip(shape)
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon()
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.W600,
            color = if (filled) Color.White else MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun kindLabel(kind: BrowseType): String = when (kind) {
    BrowseType.ALBUM -> stringResource(Res.string.album).uppercase()
    BrowseType.ARTIST -> stringResource(Res.string.artist).uppercase()
    else -> stringResource(Res.string.playlist).uppercase()
}
