package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.music.bitchord.data.webdav.WebDavUploads
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.back
import com.music.bitchord.desktop.resources.cancel
import com.music.bitchord.desktop.resources.upload_notification_title
import com.music.bitchord.desktop.resources.webdav_apply_to_all
import com.music.bitchord.desktop.resources.webdav_conflict_message
import com.music.bitchord.desktop.resources.webdav_conflict_title
import com.music.bitchord.desktop.resources.webdav_keep_both
import com.music.bitchord.desktop.resources.webdav_overwrite
import com.music.bitchord.desktop.resources.webdav_skip
import com.music.bitchord.desktop.resources.webdav_upload_summary
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The upload batch's face, and the only one it has — the uploader itself is
 * headless, and the notification shade it has on the phone has no desktop
 * equivalent (design decision 9).
 *
 * Two states, driven straight off [WebDavUploads]' flows: a batch in flight
 * draws a progress line per track, and a clash suspends the whole batch on
 * the overwrite / keep-both / skip question, with "apply to all" riding the
 * checkbox — upstream's dialog, word for word, minus the phone.
 *
 * The batch starts when the dialog is composed and the dialog is the only
 * way out of it: dismissing mid-flight is refused (the close affordance is
 * the Cancel the conflict row offers — there is no batch cancellation in the
 * uploader to wire, upstream included).
 */
@Composable
fun WebDavUploadDialog(
    songs: List<com.music.bitchord.data.model.Song>,
    onDismiss: () -> Unit,
    onFinished: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val active by WebDavUploads.active.collectAsState()
    val conflict by WebDavUploads.conflict.collectAsState()
    var finished by remember { mutableStateOf<WebDavUploads.Summary?>(null) }

    LaunchedEffect(songs) {
        val summary = WebDavUploads.upload(songs)
        finished = summary
        onFinished()
    }

    Dialog(onDismissRequest = { if (finished != null) onDismiss() }) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.width(420.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                val summary = finished
                if (summary != null) {
                    Text(
                        text = stringResource(
                            Res.string.webdav_upload_summary,
                            summary.uploaded,
                            summary.skipped,
                            summary.failed,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(Res.string.back))
                    }
                } else {
                    conflict?.let { request ->
                        Text(
                            text = stringResource(Res.string.webdav_conflict_title, request.fileName),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(Res.string.webdav_conflict_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        var applyToAll by remember(request) { mutableStateOf(false) }
                        if (request.remaining > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                                Text(stringResource(Res.string.webdav_apply_to_all))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.align(Alignment.End)) {
                            TextButton(onClick = {
                                scope.launch { request.answer.complete(WebDavUploads.Resolution(WebDavUploads.Choice.SKIP, applyToAll)) }
                            }) { Text(stringResource(Res.string.webdav_skip)) }
                            TextButton(onClick = {
                                scope.launch { request.answer.complete(WebDavUploads.Resolution(WebDavUploads.Choice.KEEP_BOTH, applyToAll)) }
                            }) { Text(stringResource(Res.string.webdav_keep_both)) }
                            Button(onClick = {
                                scope.launch { request.answer.complete(WebDavUploads.Resolution(WebDavUploads.Choice.OVERWRITE, applyToAll)) }
                            }) { Text(stringResource(Res.string.webdav_overwrite)) }
                        }
                    } ?: run {
                        Text(
                            text = stringResource(Res.string.upload_notification_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Column(
                            Modifier
                                .height(220.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            songs.forEach { song ->
                                UploadTrackRow(song, active[song.videoId])
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadTrackRow(
    song: com.music.bitchord.data.model.Song,
    state: WebDavUploads.TrackState?,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Text(
            text = when (val s = state) {
                null, WebDavUploads.TrackState.Queued -> "·"
                is WebDavUploads.TrackState.Running -> "${(s.fraction * 100).toInt()}%"
                WebDavUploads.TrackState.Done -> "✓"
                WebDavUploads.TrackState.Skipped -> stringResource(Res.string.webdav_skip)
                is WebDavUploads.TrackState.Failed -> s.reason
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val fraction = (state as? WebDavUploads.TrackState.Running)?.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
            )
        }
    }
}
