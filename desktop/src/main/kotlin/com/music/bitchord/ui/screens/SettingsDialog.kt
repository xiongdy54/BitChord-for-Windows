package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ThemeSetting
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.about
import com.music.bitchord.desktop.resources.about_upstream
import com.music.bitchord.desktop.resources.appearance
import com.music.bitchord.desktop.resources.dark_theme
import com.music.bitchord.desktop.resources.lyrics
import com.music.bitchord.desktop.resources.language
import com.music.bitchord.desktop.resources.translation_language
import com.music.bitchord.desktop.resources.lyrics_sources
import com.music.bitchord.desktop.resources.synced_lyrics
import com.music.bitchord.desktop.resources.synced_lyrics_subtitle
import com.music.bitchord.desktop.resources.light_theme
import com.music.bitchord.desktop.resources.restart_to_apply
import com.music.bitchord.desktop.resources.settings
import com.music.bitchord.desktop.resources.show_nerd_stats
import com.music.bitchord.desktop.resources.show_nerd_stats_subtitle
import com.music.bitchord.desktop.resources.system
import com.music.bitchord.desktop.resources.theme
import org.jetbrains.compose.resources.stringResource

/**
 * The desktop's settings, as a modal window rather than a page: settings are
 * window-level, not content-level, so they belong to a dialog over the shell
 * (Apple Music's own settings on the desktop are a modal window too) — and
 * outside the navigation stack, where Esc would otherwise have to learn a
 * second meaning.
 *
 * Four groups, each one a switch or a small radio list over [AppSettings]:
 * appearance (the theme override the desktop build has never had), language,
 * the nerd-stats switch the player has been reading since slice 2 without a
 * way to turn it on, and the about box. Everything else upstream's sheet
 * carries waits for the slices that can serve it — audio quality is bound to
 * the resolver, downloads to the download manager.
 */
@Composable
fun SettingsDialog(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var showLyricsSources by remember { mutableStateOf(false) }
    var showTranslationLanguage by remember { mutableStateOf(false) }
    if (showLyricsSources) {
        LyricsSourcesDialog(onDismiss = { showLyricsSources = false })
    }
    if (showTranslationLanguage) {
        TranslationLanguageDialog(onDismiss = { showTranslationLanguage = false })
    }
    Dialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = modifier.widthIn(max = 420.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                GroupHeader(stringResource(Res.string.appearance))
                val themeSetting by AppSettings.themeSetting.collectAsState()
                RadioRow(
                    label = stringResource(Res.string.system),
                    selected = themeSetting == ThemeSetting.SYSTEM,
                ) { AppSettings.setThemeSetting(ThemeSetting.SYSTEM) }
                RadioRow(
                    label = stringResource(Res.string.light_theme),
                    selected = themeSetting == ThemeSetting.LIGHT,
                ) { AppSettings.setThemeSetting(ThemeSetting.LIGHT) }
                RadioRow(
                    label = stringResource(Res.string.dark_theme),
                    selected = themeSetting == ThemeSetting.DARK,
                ) { AppSettings.setThemeSetting(ThemeSetting.DARK) }

                GroupDivider()

                GroupHeader(stringResource(Res.string.language))
                val language by AppSettings.language.collectAsState()
                RadioRow(
                    label = stringResource(Res.string.system),
                    selected = language == null,
                ) { AppSettings.setLanguage(null) }
                RadioRow(
                    label = "English",
                    selected = language == "en",
                ) { AppSettings.setLanguage("en") }
                RadioRow(
                    label = "中文",
                    selected = language == "zh",
                ) { AppSettings.setLanguage("zh") }
                Text(
                    text = stringResource(Res.string.restart_to_apply),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                GroupDivider()

                val showNerdStats by AppSettings.showNerdStats.collectAsState()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { AppSettings.setShowNerdStats(!showNerdStats) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.show_nerd_stats),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(Res.string.show_nerd_stats_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Switch(
                        checked = showNerdStats,
                        onCheckedChange = { AppSettings.setShowNerdStats(it) },
                    )
                }

                GroupDivider()

                // The lyric settings, hanging where slice 4's groups hang. The
                // synced-lyrics switch is a row like the nerd-stats one; the
                // two pickers are dialogs of their own, opened from here.
                GroupHeader(stringResource(Res.string.lyrics))
                val syncedLyrics by AppSettings.syncedLyrics.collectAsState()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { AppSettings.setSyncedLyrics(!syncedLyrics) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.synced_lyrics),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(Res.string.synced_lyrics_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Switch(
                        checked = syncedLyrics,
                        onCheckedChange = { AppSettings.setSyncedLyrics(it) },
                    )
                }
                Text(
                    text = stringResource(Res.string.lyrics_sources),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLyricsSources = true }
                        .padding(vertical = 8.dp),
                )
                Text(
                    text = stringResource(Res.string.translation_language),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTranslationLanguage = true }
                        .padding(vertical = 8.dp),
                )

                GroupDivider()

                GroupHeader(stringResource(Res.string.about))
                Text(
                    text = "BitChord ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Text(
                    text = stringResource(Res.string.about_upstream),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 10.dp),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
