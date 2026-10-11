package com.music.bitchord.ui.player

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.music.bitchord.desktop.playback.SleepTimer
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.after_this_song
import com.music.bitchord.desktop.resources.convert_to_audio
import com.music.bitchord.desktop.resources.convert_to_video
import com.music.bitchord.desktop.resources.sleep_timer
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.LocalSongActions
import com.music.bitchord.ui.components.rememberSongActionLabels
import com.music.bitchord.ui.components.songMenuItems
import org.jetbrains.compose.resources.stringResource

/**
 * The full-screen player's right-click menu, over the same `ContextMenuArea`
 * the rows use — the desktop's answer to upstream opening its
 * `SongActionsSheet` *from the player* (`MainActivity.kt:2116`), where the
 * sheet carries the playing song's every action plus the sleep timer row that
 * only a player can offer.
 *
 * The items are exactly that: the current song's row menu (the same builder
 * the rows evaluate, so nothing here can drift from what a row offers) with
 * the sleep timer row on top. The timer leads because it is about *this
 * playback* — the session, not the song — and it is the one entry that has no
 * visible equivalent anywhere else in the shell; the label carries the armed
 * state (`Sleep timer · 43:12`, or the after-this-song wording), read fresh
 * off the same flows the dialog ticks, so an open menu never describes a
 * state the tap behind it won't act on.
 *
 * The area is layout-neutral, like its row sibling: the player inside measures
 * exactly as it did unwrapped, and the swallow-presses pointer input the shell
 * hangs on the player's root keeps doing its job — the context gesture is the
 * ancestor's own detector, and the rows' clickable children have already
 * proven a descendant's consumed press does not starve it.
 *
 * [song] is the one the player is holding when the area composed — a player
 * with nothing in it still offers the sleep timer, which is about the session
 * and not about any track.
 */
@Composable
fun PlayerContextMenuArea(
    song: Song?,
    onOpenSleepTimer: () -> Unit,
    onToggleAudioVersion: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val actions = LocalSongActions.current
    if (actions == null) {
        content()
        return
    }
    val labels = rememberSongActionLabels()
    // Both read in composition, so the one-second countdown tick recomposes
    // this area and the builder below — which the platform calls outside
    // composition — captures a label that is at most one second old.
    val remaining = sleepTimerRemaining()
    val afterTrack by SleepTimer.afterTrack.collectAsState()
    val sleepTimerRow = when {
        remaining != null -> {
            val base = stringResource(Res.string.sleep_timer)
            "$base · ${formatSleepCountdown(remaining)}"
        }
        afterTrack -> {
            val base = stringResource(Res.string.sleep_timer)
            val suffix = stringResource(Res.string.after_this_song)
            "$base · $suffix"
        }
        else -> stringResource(Res.string.sleep_timer)
    }
    // The version row (slice 21): the playing row's other self, worded by its
    // own orientation — a video row offers the catalogue song, a song row the
    // video. Null (local files, WebDAV rows — nothing YouTube has a second
    // version of) and the row simply isn't offered.
    val versionRow = song?.let {
        if (onToggleAudioVersion == null) {
            null
        } else if (it.isVideo) {
            ContextMenuItem(stringResource(Res.string.convert_to_audio)) { onToggleAudioVersion() }
        } else {
            ContextMenuItem(stringResource(Res.string.convert_to_video)) { onToggleAudioVersion() }
        }
    }
    ContextMenuArea(
        items = {
            buildList {
                add(ContextMenuItem(sleepTimerRow) { onOpenSleepTimer() })
                if (versionRow != null) add(versionRow)
                if (song != null) addAll(songMenuItems(song, labels, actions))
            }
        },
    ) {
        content()
    }
}
