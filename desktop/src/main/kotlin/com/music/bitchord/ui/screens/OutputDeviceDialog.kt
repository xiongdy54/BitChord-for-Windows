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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.music.bitchord.desktop.playback.AudioOutputDevice
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.audio_output
import com.music.bitchord.desktop.resources.system_default
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * The sound endpoint picker, as a dialog off the settings' playback group —
 * the desktop's answer to upstream's `AudioOutputSheet`, whose Android
 * routing world (Bluetooth, wired, cast targets) has no counterpart here: on
 * this platform there is exactly one machine's worth of endpoints and the
 * question is only which of them carries the sound.
 *
 * The list enumerates once per open, on an IO dispatcher — the engine's
 * enumeration is a blocking native call and the dialog is the only thing that
 * ever asks for it. A device chosen in a previous session that no longer
 * enumerates (unplugged, renamed) simply does not appear;〔系统默认〕is always
 * present and is the way back, which is also what the platform itself falls
 * back to when the chosen endpoint disappears from under it.
 *
 * The selection goes through [onSelect] — null meaning the system default —
 * which is the player's own entry point in production: the setting persists
 * and the engine hears about it in the same act.
 */
@Composable
fun OutputDeviceDialog(
    onDismiss: () -> Unit,
    /** The engine's enumeration — blocking, so the dialog runs it off the UI thread. */
    enumerateDevices: () -> List<AudioOutputDevice>,
    /** The id currently stored, or null for the system default. */
    onSelect: (AudioOutputDevice?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier.widthIn(max = 420.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            OutputDevicePanel(
                enumerateDevices = enumerateDevices,
                onSelect = { onSelect(it); onDismiss() },
            )
        }
    }
}

/**
 * The picker's rows, without the window around them — the dialog's whole body,
 * and the screenshot probe's way of showing it (a dialog inside the settings
 * dialog renders in its own native window, which the capture pipeline cannot
 * see; the preview renders this panel inline, the menu preview's precedent).
 */
@Composable
fun OutputDevicePanel(
    enumerateDevices: () -> List<AudioOutputDevice>,
    onSelect: (AudioOutputDevice?) -> Unit,
) {
    val storedId by AppSettings.outputDeviceId.collectAsState()
    var devices by remember { mutableStateOf<List<AudioOutputDevice>?>(null) }
    LaunchedEffect(Unit) {
        devices = withContext(Dispatchers.IO) { enumerateDevices() }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(Res.string.audio_output),
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        DeviceRow(
            label = stringResource(Res.string.system_default),
            selected = storedId == null,
        ) { onSelect(null) }

        val listed = devices
        when {
            listed == null -> Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.height(20.dp))
            }
            listed.isEmpty() -> Text(
                // An engine that cannot enumerate says so rather than drawing
                // an empty list the user would read as "no devices exist".
                text = "—",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            else -> listed.forEach { device ->
                DeviceRow(
                    label = device.name,
                    selected = storedId == device.id,
                ) { onSelect(device) }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun DeviceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
