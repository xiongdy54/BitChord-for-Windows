package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.couldnt_load_tracks
import com.music.bitchord.desktop.resources.nothing_to_explore
import com.music.bitchord.desktop.resources.retry
import com.music.bitchord.ui.ExploreViewModel
import com.music.bitchord.ui.components.PAGE_GUTTER
import org.jetbrains.compose.resources.stringResource
import kotlin.math.absoluteValue

/**
 * The browse page: YouTube Music's moods and genres as rows of coloured
 * chips, and — one tap deeper — the shelves a genre opens into, drawn by the
 * same carousel the home feed draws.
 *
 * The chips are the layout's one invention: YouTube colours them server-side
 * with no value in the payload, so the colour is derived from the title —
 * stable for a given genre, spread evenly around the wheel the way the grid
 * reads in the app.
 */
@Composable
fun ExploreScreen(
    viewModel: ExploreViewModel,
    onOpenGenre: (MoodGenre) -> Unit,
    onShelfItemClick: (ShelfItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections by viewModel.sections.collectAsState()
    val openGenre by viewModel.openGenre.collectAsState()
    val shelves by viewModel.genreShelves.collectAsState()

    LaunchedEffect(Unit) { viewModel.ensureSections() }

    // Inside a genre until it is closed — the shelves page replaces the grid,
    // the same trade the Android explore page makes, and back out via the
    // header's tap rather than a second scroll position.
    val genre = openGenre
    if (genre != null) {
        GenreShelvesPage(
            title = genre.title,
            shelves = shelves,
            onBack = viewModel::closeGenre,
            onShelfItemClick = onShelfItemClick,
            modifier = modifier,
        )
        return
    }

    when (val value = sections) {
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
                        .clickable { viewModel.retrySections() }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }

        is UiState.Success -> {
            if (value.data.isEmpty()) {
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(Res.string.nothing_to_explore),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = modifier.fillMaxSize()) {
                    itemsIndexed(value.data) { _, section ->
                        Column {
                            SectionHeader(section.title)
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // The browse id alone is not a key: YouTube
                                // reuses one category id across rows that
                                // differ only in their params.
                                items(
                                    section.items,
                                    key = { "${it.browseId}:${it.params.orEmpty()}" },
                                ) { genre ->
                                    MoodChip(genre = genre, onClick = { onOpenGenre(genre) })
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

/** A genre opened into its shelves; the same body a home feed draws, under the genre's name. */
@Composable
private fun GenreShelvesPage(
    title: String,
    shelves: UiState<List<HomeShelf>>,
    onBack: () -> Unit,
    onShelfItemClick: (ShelfItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
                .clickable(onClick = onBack),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        when (val value = shelves) {
            is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(Res.string.couldnt_load_tracks),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is UiState.Success -> LazyColumn(state = rememberLazyListState()) {
                itemsIndexed(value.data) { _, shelf ->
                    Box(Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp)) {
                        Shelf(shelf = shelf, onItemClick = onShelfItemClick)
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

/** A mood chip: its title in white over a colour derived from its own name. */
@Composable
private fun MoodChip(genre: MoodGenre, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(132.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(genreColor(genre.title))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            genre.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.W600,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A stable hue per title, mid-saturation, mid-lightness — close enough to the
 * app's own palette spread that a row of them reads the same way. Hashed, so
 * the same genre is the same colour on every launch.
 */
private fun genreColor(title: String): Color {
    val hue = (title.hashCode().absoluteValue % 360).toFloat()
    return Color.hsl(hue = hue, saturation = 0.55f, lightness = 0.48f)
}
