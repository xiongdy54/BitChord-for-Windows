package com.music.bitchord.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.music.bitchord.desktop.playback.SleepTimer
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.after_this_song
import com.music.bitchord.desktop.resources.one_hour
import com.music.bitchord.desktop.resources.pause_after_a_while
import com.music.bitchord.desktop.resources.pause_after_song
import com.music.bitchord.desktop.resources.pause_countdown
import com.music.bitchord.desktop.resources.sleep_timer
import com.music.bitchord.desktop.resources.turn_off_timer
import java.util.Locale
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import com.music.bitchord.desktop.resources.minute_count

/**
 * The sleep timer's picker, as a window-modal dialog — upstream's
 * `SleepTimerPicker` (`SongActionsSheet.kt:575`) with the bottom sheet's
 * back-stack replaced by the dialog's own dismissal.
 *
 * Finishing the song is the one people reach for at the end of a listen, so it
 * leads rather than sitting under the durations — upstream's order, kept. The
 * status line under the title ticks every second while a deadline runs, which
 * is the whole countdown the desktop shows: an armed timer otherwise lives
 * only in this dialog and the player menu row that opens it.
 */
@Composable
fun SleepTimerDialog(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val chosen by SleepTimer.minutes.collectAsState()
    val afterTrack by SleepTimer.afterTrack.collectAsState()
    val remaining = sleepTimerRemaining()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier.widthIn(max = 360.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    text = stringResource(Res.string.sleep_timer),
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        remaining != null -> stringResource(
                            Res.string.pause_countdown,
                            formatSleepCountdown(remaining),
                        )
                        afterTrack -> stringResource(Res.string.pause_after_song)
                        else -> stringResource(Res.string.pause_after_a_while)
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(
                    Modifier.padding(top = 12.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Spacer(Modifier.height(4.dp))

                SleepOptionRow(
                    label = stringResource(Res.string.after_this_song),
                    selected = afterTrack,
                ) {
                    SleepTimer.startAfterTrack()
                    onDismiss()
                }
                SleepTimer.PRESETS.forEach { minutes ->
                    SleepOptionRow(
                        label = sleepTimerPresetLabel(minutes),
                        selected = minutes == chosen,
                    ) {
                        SleepTimer.start(minutes)
                        onDismiss()
                    }
                }
                if (SleepTimer.isRunning) {
                    SleepOptionRow(
                        label = stringResource(Res.string.turn_off_timer),
                        selected = false,
                        icon = Icons.Rounded.Close,
                    ) {
                        SleepTimer.cancel()
                        onDismiss()
                    }
                }
            }
        }
    }
}

/**
 * One picker row: the label, and a trailing glyph. Selected rows carry a
 * checkmark; [icon] overrides it — the turn-off row is the only one whose act
 * removes state, and it says so with a close glyph instead of a checkmark.
 */
@Composable
private fun SleepOptionRow(
    label: String,
    selected: Boolean,
    icon: ImageVector = Icons.Rounded.Check,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        // A checkmark only on the armed row; the turn-off row speaks with its
        // own glyph. An unselected duration row carries nothing — a checkmark
        // in grey is a row that looks half-chosen.
        if (selected || icon != Icons.Rounded.Check) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The picker's word for a preset: minutes, except 60, which reads as one hour. */
@Composable
fun sleepTimerPresetLabel(minutes: Int): String =
    if (minutes == 60) {
        stringResource(Res.string.one_hour)
    } else {
        pluralStringResource(Res.plurals.minute_count, minutes, minutes)
    }

/**
 * Live "m:ss" until the sleep timer fires, or null when none is running —
 * upstream's `sleepTimerCountdown`, ticking once a second and restarting with
 * the deadline so a re-armed timer re-seats the count instead of racing it.
 */
@Composable
fun sleepTimerRemaining(): Long? {
    val deadline by SleepTimer.deadline.collectAsState()
    val remaining = produceState<Long?>(SleepTimer.remainingMs(), deadline) {
        while (deadline != null) {
            value = SleepTimer.remainingMs()
            delay(1_000)
        }
        value = null
    }
    return remaining.value
}

/** `%d:%02d` on the monotonic remaining, Locale.ROOT — upstream's exact format. */
fun formatSleepCountdown(remainingMs: Long): String {
    val seconds = remainingMs / 1000
    return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}
