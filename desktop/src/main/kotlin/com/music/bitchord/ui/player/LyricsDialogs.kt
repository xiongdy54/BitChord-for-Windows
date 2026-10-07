package com.music.bitchord.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.desktop.playback.LyricsProviderState
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.choose_lyrics_provider
import com.music.bitchord.desktop.resources.close
import com.music.bitchord.desktop.resources.decrease_lyrics_offset
import com.music.bitchord.desktop.resources.increase_lyrics_offset
import com.music.bitchord.desktop.resources.lyrics_offset
import com.music.bitchord.desktop.resources.lyrics_offset_description
import com.music.bitchord.desktop.resources.lyrics_offset_reset
import com.music.bitchord.desktop.resources.lyrics_provider_current
import com.music.bitchord.desktop.resources.lyrics_provider_fetching
import com.music.bitchord.desktop.resources.lyrics_provider_found
import com.music.bitchord.desktop.resources.lyrics_provider_not_fetched
import com.music.bitchord.desktop.resources.lyrics_provider_not_found
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * The player's two lyric dialogs, in the desktop's dialog shape rather than the
 * app's bottom sheets — the semantics are the sheets' ([LyricsProviderSheet] and
 * [LyricsOffsetSheet]), the surface is [com.music.bitchord.ui.screens.SettingsDialog]'s:
 * a modal window over the player, closed by Esc or the click that opened it.
 *
 * The provider list reads the coordinator's per-source states, shows the sources
 * in the user's saved order and hands the pick to [onSelect] — a completed hit
 * applies from memory, a miss is inert, an untouched source goes online. The
 * offset dialog carries the ±100ms stepper straight over, writing
 * [AppSettings.lyricsOffsetMs]; it lives here rather than in the ⋯ menu because
 * the desktop's menu belongs to the shell, not to the player — and the two
 * surfaces are one topic, so the status line opens the provider dialog and the
 * provider dialog leads to the offset.
 */

/** How far one tap of the offset stepper moves the lyrics, in milliseconds. */
private const val STEP_MS = 100

@Composable
fun LyricsProviderDialog(
    currentSource: LyricsSource?,
    states: Map<LyricsSource, LyricsProviderState>,
    onSelect: (LyricsSource) -> Unit,
    onOpenOffset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier.width(360.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = stringResource(Res.string.choose_lyrics_provider),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                val order by AppSettings.lyricsSourceOrder.collectAsState()
                val enabled by AppSettings.lyricsSources.collectAsState()
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    order.forEach { source ->
                        val state = states[source] ?: LyricsProviderState.NOT_FETCHED
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = source in enabled) {
                                    onSelect(source)
                                    onDismiss()
                                }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = source.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (source in enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = providerStateLabel(currentSource, source, state),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onOpenOffset) {
                        Text(stringResource(Res.string.lyrics_offset))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.close))
                    }
                }
            }
        }
    }
}

@Composable
private fun providerStateLabel(
    currentSource: LyricsSource?,
    source: LyricsSource,
    state: LyricsProviderState,
): String = when {
    source == currentSource -> stringResource(Res.string.lyrics_provider_current)
    state == LyricsProviderState.FOUND -> stringResource(Res.string.lyrics_provider_found)
    state == LyricsProviderState.NOT_FOUND -> stringResource(Res.string.lyrics_provider_not_found)
    state == LyricsProviderState.FETCHING -> stringResource(Res.string.lyrics_provider_fetching)
    else -> stringResource(Res.string.lyrics_provider_not_fetched)
}

@Composable
fun LyricsOffsetDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier.width(360.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = stringResource(Res.string.lyrics_offset),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.lyrics_offset_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { AppSettings.setLyricsOffsetMs(AppSettings.lyricsOffsetMs.value - STEP_MS) }) {
                        Icon(Icons.Rounded.Remove, contentDescription = stringResource(Res.string.decrease_lyrics_offset))
                    }
                    Text(
                        text = formatOffsetMs(AppSettings.lyricsOffsetMs.value),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { AppSettings.setLyricsOffsetMs(AppSettings.lyricsOffsetMs.value + STEP_MS) }) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(Res.string.increase_lyrics_offset))
                    }
                }
                TextButton(onClick = { AppSettings.setLyricsOffsetMs(0) }) {
                    Text(stringResource(Res.string.lyrics_offset_reset))
                }
            }
        }
    }
}

/** `1,250 ms` — snapped to steps, like the sheet's own rounding. */
private fun formatOffsetMs(raw: Int): String =
    "${(raw.toFloat() / STEP_MS).roundToInt() * STEP_MS} ms"
