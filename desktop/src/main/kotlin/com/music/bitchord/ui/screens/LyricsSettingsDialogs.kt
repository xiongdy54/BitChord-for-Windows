package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.music.bitchord.data.lyrics.TRANSLATION_LANGUAGES
import com.music.bitchord.data.lyrics.TranslationLanguage
import com.music.bitchord.data.lyrics.translationLanguageName
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.cancel
import com.music.bitchord.desktop.resources.done
import com.music.bitchord.desktop.resources.lyrics_sources
import com.music.bitchord.desktop.resources.lyrics_sources_order
import com.music.bitchord.desktop.resources.paxsenix_api_key
import com.music.bitchord.desktop.resources.paxsenix_api_key_configured
import com.music.bitchord.desktop.resources.paxsenix_api_key_missing
import com.music.bitchord.desktop.resources.prioritize_syllable_lyrics
import com.music.bitchord.desktop.resources.prioritize_syllable_lyrics_subtitle
import com.music.bitchord.desktop.resources.reset_to_default
import com.music.bitchord.desktop.resources.save
import com.music.bitchord.desktop.resources.translation_language
import com.music.bitchord.desktop.resources.translation_language_app_default
import com.music.bitchord.desktop.resources.translation_language_description
import java.util.Locale
import org.jetbrains.compose.resources.stringResource

/**
 * The two lyric settings dialogs, in the desktop's dialog shape.
 *
 * Upstream ships these as haze cards (`LyricsSourcesDialog`,
 * `TranslationLanguageDialog`); the semantics — the source list with its order,
 * the syllable-sync switch, the PaxSenix key, the 131-language picker — come
 * over whole, dressed as [SettingsDialog] is. Two deliberate shape changes,
 * both recorded here rather than rediscovered later:
 *
 *  1. **Reorder buttons instead of drag.** The app's `ReorderableSourceList`
 *     measures a finger against a fixed row pitch; a desktop list is mouse and
 *     keyboard first, and a pair of arrows beside each row is a target a
 *     pointer never misses. The order itself is the same persisted permutation.
 *  2. **The PaxSenix key is an inline field** rather than a sub-dialog with
 *     save/cancel — a text field in a dialog is the native form on this
 *     platform, and the write-through setter needs no ceremony around it.
 */
@Composable
fun LyricsSourcesDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected by AppSettings.lyricsSources.collectAsState()
    val savedOrder by AppSettings.lyricsSourceOrder.collectAsState()
    val prioritizeSyllableSync by AppSettings.prioritizeSyllableSync.collectAsState()
    val paxSenixApiKey by AppSettings.paxSenixApiKey.collectAsState()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier.width(440.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = stringResource(Res.string.lyrics_sources),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.lyrics_sources_order),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    savedOrder.forEachIndexed { index, source ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = source in selected,
                                onCheckedChange = { checked ->
                                    val next = selected.toMutableSet()
                                    if (checked) next += source else next -= source
                                    AppSettings.setLyricsSources(next)
                                },
                            )
                            Text(
                                text = source.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                enabled = index > 0,
                                onClick = {
                                    val next = savedOrder.toMutableList()
                                    next[index - 1] = next[index].also { next[index] = next[index - 1] }
                                    AppSettings.setLyricsSourceOrder(next)
                                },
                            ) {
                                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null)
                            }
                            IconButton(
                                enabled = index < savedOrder.lastIndex,
                                onClick = {
                                    val next = savedOrder.toMutableList()
                                    next[index + 1] = next[index].also { next[index] = next[index + 1] }
                                    AppSettings.setLyricsSourceOrder(next)
                                },
                            ) {
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                            }
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { AppSettings.setPrioritizeSyllableSync(!prioritizeSyllableSync) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.prioritize_syllable_lyrics),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(Res.string.prioritize_syllable_lyrics_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = prioritizeSyllableSync,
                        onCheckedChange = { AppSettings.setPrioritizeSyllableSync(it) },
                    )
                }

                Text(
                    text = stringResource(
                        if (paxSenixApiKey.isBlank()) Res.string.paxsenix_api_key
                        else Res.string.paxsenix_api_key_configured,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
                OutlinedTextField(
                    value = paxSenixApiKey,
                    onValueChange = { AppSettings.setPaxSenixApiKey(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = AppSettings::resetLyricsSourceSettings) {
                        Text(stringResource(Res.string.reset_to_default))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.done))
                    }
                }
            }
        }
    }
}

/**
 * Where the translate button sends the words, as a searchable list. Names are
 * resolved once per locale rather than per row per frame — the same economy
 * upstream's copy makes — and the query matches the English name as well as
 * the localised one, so someone reading the app in Hindi can still type
 * "Japanese".
 */
@Composable
fun TranslationLanguageDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected by AppSettings.translationLanguage.collectAsState()
    val appLocale = Locale.getDefault()
    var query by remember { mutableStateOf("") }

    val named = remember(appLocale) {
        TRANSLATION_LANGUAGES.map { it to translationLanguageName(it.code, appLocale) }
    }
    val shown = remember(named, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            named
        } else {
            named.filter { (language: TranslationLanguage, name: String) ->
                name.contains(needle, ignoreCase = true) ||
                    language.fallbackName.contains(needle, ignoreCase = true)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier.width(360.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = stringResource(Res.string.translation_language),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.translation_language_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 6.dp)) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { AppSettings.setTranslationLanguage("") }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected.isBlank(),
                                onClick = { AppSettings.setTranslationLanguage("") },
                            )
                            Text(
                                text = stringResource(Res.string.translation_language_app_default),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    items(shown) { (language, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { AppSettings.setTranslationLanguage(language.code) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == language.code,
                                onClick = { AppSettings.setTranslationLanguage(language.code) },
                            )
                            Text(text = name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            }
        }
    }
}
