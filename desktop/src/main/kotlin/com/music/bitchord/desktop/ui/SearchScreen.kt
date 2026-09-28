package com.music.bitchord.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.desktop.playback.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The whole MVP: search a song, tap it, hear it. Everything else the Android
 * app does is meant to grow around this screen rather than replace it.
 */
@Composable
fun SearchScreen(
    player: PlayerController,
    initialQuery: String = "",
    autoPlayFirst: Boolean = false,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val current by player.current.collectAsState()

    // Debounced: typing cancels the previous search rather than racing it.
    LaunchedEffect(query) {
        val text = query.trim()
        if (text.isEmpty()) {
            results = emptyList()
            error = null
            return@LaunchedEffect
        }
        delay(400)
        searching = true
        error = null
        withContext(Dispatchers.IO) { YtMusicRepository.search(text, SearchFilter.ALL) }
            .onSuccess { rows ->
                results = rows
                if (autoPlayFirst && player.current.value == null) {
                    rows.firstNotNullOfOrNull { row ->
                        when (row) {
                            is SearchResult.TopTrack -> row.song
                            is SearchResult.Track -> row.song
                            is SearchResult.Browse -> null
                        }
                    }?.let(player::play)
                }
            }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
        searching = false
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 28.dp)) {
            Text(
                "BitChord",
                fontSize = 30.sp,
                fontWeight = FontWeight.W800,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(16.dp))
            SearchField(query, searching) { query = it }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(18.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(results, key = { it.key() }) { row ->
                val song = row.song()
                ResultRow(row, isCurrent = song != null && song.videoId == current?.videoId) {
                    song?.let(player::play)
                }
            }
        }

        PlayerBar(player)
    }
}

@Composable
private fun SearchField(value: String, busy: Boolean, onValueChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                if (busy) "Searching…" else "Search YouTube Music",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ResultRow(row: SearchResult, isCurrent: Boolean, onClick: () -> Unit) {
    val song = row.song()
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = song != null, onClick = onClick)
            .background(
                if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            )
            .padding(horizontal = 32.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(row.thumbnailUrl(), size = 46.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.title(),
                fontSize = 15.sp,
                fontWeight = FontWeight.W600,
                color = if (song != null) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.subtitle(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        song?.durationText?.let {
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Artwork(url: String?, size: Dp) {
    Box(
        Modifier.size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PlayerBar(player: PlayerController) {
    val song by player.current.collectAsState()
    val playing by player.playing.collectAsState()
    val position by player.positionMs.collectAsState()
    val duration by player.durationMs.collectAsState()
    val volume by player.volume.collectAsState()
    val status by player.status.collectAsState()

    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 32.dp, vertical = 14.dp),
    ) {
        status?.let {
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(song?.thumbnailUrl, size = 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.width(240.dp)) {
                Text(
                    song?.title ?: "Nothing playing",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song?.artist.orEmpty(),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(18.dp))
            Text(
                if (playing) "⏸" else "▶",
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable { player.togglePlayPause() },
            )
            Spacer(Modifier.width(16.dp))
            Text(clock(position), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { player.seekToFraction(it) },
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text(clock(duration), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(18.dp))
            Text("🔊", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = volume.toFloat() / 100f,
                onValueChange = { player.setVolume((it * 100).toInt()) },
                modifier = Modifier.width(110.dp).padding(start = 8.dp),
            )
        }
    }
}

private fun clock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun SearchResult.song(): Song? = when (this) {
    is SearchResult.TopTrack -> song
    is SearchResult.Track -> song
    is SearchResult.Browse -> null
}

private fun SearchResult.key(): String = when (this) {
    is SearchResult.TopTrack -> "top:${song.videoId}"
    is SearchResult.Track -> "song:${song.videoId}"
    is SearchResult.Browse -> "browse:${item.browseId}"
}

private fun SearchResult.title(): String = when (this) {
    is SearchResult.TopTrack -> song.title
    is SearchResult.Track -> song.title
    is SearchResult.Browse -> item.title
}

private fun SearchResult.subtitle(): String = when (this) {
    is SearchResult.TopTrack -> "${song.artist} · Top result"
    is SearchResult.Track -> song.artist
    is SearchResult.Browse -> item.subtitle
}

private fun SearchResult.thumbnailUrl(): String? = when (this) {
    is SearchResult.TopTrack -> song.thumbnailUrl
    is SearchResult.Track -> song.thumbnailUrl
    is SearchResult.Browse -> item.thumbnailUrl
}
