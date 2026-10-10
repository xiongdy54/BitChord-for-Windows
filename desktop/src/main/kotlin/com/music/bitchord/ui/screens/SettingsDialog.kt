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
import com.music.bitchord.auth.AccountController
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.settings.ThemeSetting
import com.music.bitchord.desktop.playback.AudioOutputDevice
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.about
import com.music.bitchord.desktop.resources.about_upstream
import com.music.bitchord.desktop.resources.account
import com.music.bitchord.desktop.resources.appearance
import com.music.bitchord.desktop.resources.audio_output
import com.music.bitchord.desktop.resources.audio_quality
import com.music.bitchord.desktop.resources.autoplay
import com.music.bitchord.desktop.resources.autoplay_subtitle
import com.music.bitchord.desktop.resources.dark_theme
import com.music.bitchord.desktop.resources.library_sign_in_description
import com.music.bitchord.desktop.resources.listen_as
import com.music.bitchord.desktop.resources.lyrics
import com.music.bitchord.desktop.resources.download_quality
import com.music.bitchord.desktop.resources.language
import com.music.bitchord.desktop.resources.playback
import com.music.bitchord.desktop.resources.sign_in_youtube_music
import com.music.bitchord.desktop.resources.sign_out
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
import com.music.bitchord.desktop.resources.system_default
import com.music.bitchord.desktop.resources.theme
import com.music.bitchord.desktop.resources.translucent_window
import com.music.bitchord.desktop.resources.translucent_window_subtitle
import org.jetbrains.compose.resources.stringResource

/**
 * The desktop's settings, as a modal window rather than a page: settings are
 * window-level, not content-level, so they belong to a dialog over the shell
 * (Apple Music's own settings on the desktop are a modal window too) — and
 * outside the navigation stack, where Esc would otherwise have to learn a
 * second meaning.
 *
 * Five groups, each one a switch, a row or a small radio list: the account —
 * the "who am I" question the signed-in features hang from, whose sign-in act
 * opens the login window — appearance, language, the lyrics cluster, and the
 * about box. Everything else upstream's sheet carries waits for the slices
 * that can serve it — audio quality is bound to the resolver, downloads to
 * the download manager.
 */
@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    /** The quality switch goes through the player, whose swap seam answers to it. */
    onAudioQualityChanged: (AudioQuality) -> Unit,
    /**
     * The session registry and its flows — the account group reads who the app
     * is acting as from it and routes every identity act through it. Kept a
     * parameter like the engine's device enumeration: this dialog stays a
     * surface, the controller stays the actor.
     */
    accountController: AccountController,
    /** Opens the login window; the mode says whether it signs in or switches. */
    onOpenLogin: (WebSessionMode) -> Unit,
    /** The engine's endpoint enumeration — a blocking native call, handed over as a lambda. */
    enumerateAudioOutputDevices: () -> List<AudioOutputDevice> = { emptyList() },
    /** The device switch goes through the player too, which owns the engine call. */
    onAudioOutputDeviceChanged: (AudioOutputDevice?) -> Unit = {},
    /** One-shot from the screenshot probe: arrive with the output picker already open. */
    autoOpenAudioOutput: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showLyricsSources by remember { mutableStateOf(false) }
    var showTranslationLanguage by remember { mutableStateOf(false) }
    var showAudioOutput by remember { mutableStateOf(autoOpenAudioOutput) }
    var showAccountDialog by remember { mutableStateOf(false) }
    if (showLyricsSources) {
        LyricsSourcesDialog(onDismiss = { showLyricsSources = false })
    }
    if (showTranslationLanguage) {
        TranslationLanguageDialog(onDismiss = { showTranslationLanguage = false })
    }
    if (showAudioOutput) {
        OutputDeviceDialog(
            onDismiss = { showAudioOutput = false },
            enumerateDevices = enumerateAudioOutputDevices,
            onSelect = onAudioOutputDeviceChanged,
        )
    }
    if (showAccountDialog) {
        AccountDialog(
            controller = accountController,
            onOpenLogin = onOpenLogin,
            onDismiss = { showAccountDialog = false },
        )
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

                // The "who am I" question, at the top because every other
                // group's content depends on its answer. Signed out is one
                // row; signed in is the identity, the way to change it, and
                // the way out — the same three acts the phone's avatar menu
                // offers, gathered where the desktop gathers decisions.
                GroupHeader(stringResource(Res.string.account))
                val signedIn by accountController.signedIn.collectAsState()
                if (!signedIn) {
                    ClickableRow(
                        title = stringResource(Res.string.sign_in_youtube_music),
                        subtitle = stringResource(Res.string.library_sign_in_description),
                    ) { onOpenLogin(WebSessionMode.SIGN_IN) }
                } else {
                    val activeAccount by accountController.activeAccountId.collectAsState()
                    val accounts by accountController.accounts.collectAsState()
                    val stored = accounts.firstOrNull { it.accountId == activeAccount }
                    // The identity row states a fact and takes no click; the
                    // rows under it are the acts.
                    ClickableRow(
                        title = stored?.name?.ifBlank { null }
                            ?: stringResource(Res.string.account),
                        subtitle = stored?.email?.ifBlank { null },
                        onClick = null,
                    )
                    ClickableRow(
                        title = stringResource(Res.string.listen_as),
                        subtitle = stored?.profiles
                            ?.firstOrNull { it.profileId == stored.activeProfileId }?.name,
                    ) { showAccountDialog = true }
                    ClickableRow(
                        title = stringResource(Res.string.sign_out),
                        destructive = true,
                    ) { accountController.signOut() }
                }

                GroupDivider()

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

                // The window material: Mica behind translucent chrome, or an
                // opaque frame without it — the opaque frame being the one that
                // resizes smoothly, which the subtitle says. Next-launch by
                // design; the platform cannot re-cut a shown window's
                // transparency, which is what the note under the row says.
                val translucentWindow by AppSettings.translucentWindow.collectAsState()
                SwitchRow(
                    title = stringResource(Res.string.translucent_window),
                    subtitle = stringResource(Res.string.translucent_window_subtitle),
                    checked = translucentWindow,
                ) { AppSettings.setTranslucentWindow(it) }
                Text(
                    text = stringResource(Res.string.restart_to_apply),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

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

                // The queue's own appetite. The switch writes the setting; the
                // player controller's collector owns what the queue does about
                // it, so this row and the queue panel's heading are two faces
                // of one control.
                GroupHeader(stringResource(Res.string.playback))
                val autoplayEnabled by AppSettings.autoplay.collectAsState()
                SwitchRow(
                    title = stringResource(Res.string.autoplay),
                    subtitle = stringResource(Res.string.autoplay_subtitle),
                    checked = autoplayEnabled,
                ) { AppSettings.setAutoplay(it) }

                GroupDivider()

                val showNerdStats by AppSettings.showNerdStats.collectAsState()
                SwitchRow(
                    title = stringResource(Res.string.show_nerd_stats),
                    subtitle = stringResource(Res.string.show_nerd_stats_subtitle),
                    checked = showNerdStats,
                ) { AppSettings.setShowNerdStats(it) }

                // The endpoint the sound goes out on. The value names the
                // stored device even after it stops enumerating — the name
                // rides beside the id for exactly that — and the always
                // -present system default is the way back.
                val outputDeviceName by AppSettings.outputDeviceName.collectAsState()
                Text(
                    text = stringResource(Res.string.audio_output),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAudioOutput = true }
                        .padding(top = 8.dp),
                )
                Text(
                    text = outputDeviceName
                        ?: stringResource(Res.string.system_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                GroupDivider()

                // The quality ladders: what the player resolves under and what a
                // download keeps, each read back next launch like the theme is.
                // The playback ceiling's seam is StreamResolver's own; the
                // download's is read once per track by the pipeline.
                GroupHeader(stringResource(Res.string.audio_quality))
                val audioQuality by AppSettings.audioQuality.collectAsState()
                AudioQuality.entries.forEach { quality ->
                    RadioRow(
                        label = quality.label,
                        selected = audioQuality == quality,
                    ) { onAudioQualityChanged(quality) }
                }

                GroupDivider()

                GroupHeader(stringResource(Res.string.download_quality))
                val downloadQuality by AppSettings.downloadQuality.collectAsState()
                DownloadQuality.entries.forEach { quality ->
                    RadioRow(
                        label = quality.label,
                        selected = downloadQuality == quality,
                    ) { AppSettings.setDownloadQuality(quality) }
                }

                GroupDivider()

                // The lyric settings, hanging where slice 4's groups hang. The
                // synced-lyrics switch is a row like the nerd-stats one; the
                // two pickers are dialogs of their own, opened from here.
                GroupHeader(stringResource(Res.string.lyrics))
                val syncedLyrics by AppSettings.syncedLyrics.collectAsState()
                SwitchRow(
                    title = stringResource(Res.string.synced_lyrics),
                    subtitle = stringResource(Res.string.synced_lyrics_subtitle),
                    checked = syncedLyrics,
                ) { AppSettings.setSyncedLyrics(it) }
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

/**
 * A row that answers a click somewhere else — the sign-in offer, the child
 * dialogs, sign-out — or, with a null [onClick], one that only states a fact
 * (the signed-in identity). The whole row is the target, not a trailing
 * glyph; the output-device row set that precedent.
 */
@Composable
private fun ClickableRow(
    title: String,
    subtitle: String? = null,
    destructive: Boolean = false,
    onClick: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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

/**
 * The title-and-subtitle switch row the dialog's toggles share. The whole row
 * answers a click, not just the switch on its end — a control this small is a
 * narrow target otherwise — which is also why the switch itself takes no
 * special treatment here.
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
        )
    }
}
