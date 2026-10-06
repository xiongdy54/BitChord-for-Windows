package com.music.bitchord.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.FilterNone
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.back
import com.music.bitchord.desktop.resources.forward
import com.music.bitchord.desktop.resources.ic_logo
import com.music.bitchord.desktop.resources.ic_player_next
import com.music.bitchord.desktop.resources.ic_player_pause
import com.music.bitchord.desktop.resources.ic_player_play
import com.music.bitchord.desktop.resources.ic_player_previous
import com.music.bitchord.desktop.resources.pause
import com.music.bitchord.desktop.resources.play
import com.music.bitchord.desktop.resources.repeat_all
import com.music.bitchord.desktop.resources.repeat_off
import com.music.bitchord.desktop.resources.repeat_one
import com.music.bitchord.desktop.resources.shuffle
import com.music.bitchord.desktop.resources.shuffle_off
import com.music.bitchord.desktop.resources.shuffle_on
import com.music.bitchord.desktop.resources.status_engine_error
import com.music.bitchord.desktop.resources.status_nothing_playable
import com.music.bitchord.desktop.resources.status_opening
import com.music.bitchord.desktop.resources.status_resolve_failed
import com.music.bitchord.desktop.resources.status_resolving
import com.music.bitchord.desktop.resources.volume
import com.music.bitchord.playback.PlaybackStatus
import com.music.bitchord.playback.RepeatMode
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.player.MarqueeText
import com.music.bitchord.ui.player.ThinSlider
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Apple Music toolbar, laid out the way the Windows app lays it out:
 * shuffle / previous / play / next / repeat on the left, the now-playing
 * display as a floating panel in the middle, the volume at the right, and the
 * window's own three buttons closing the row — one row of [TOOLBAR_HEIGHT],
 * painted in the sidebar's chrome colour so the two read as one surface.
 *
 * The whole row is the window's drag area (the undecorated window has no
 * title bar left to drag), and it spans the full window width: the sidebar
 * starts below it, exactly as the reference app draws it.
 *
 * Everything here is a *junction*, not a surface of its own: every value it
 * shows arrives as a parameter and every gesture leaves as a callback, so the
 * wiring stays in [Shell] where the controllers live. The one exception is
 * the volume scale — `PlayerController` speaks 0..100, `ThinSlider` speaks
 * 0f..1f, and the conversion sits beside the slider that needs it.
 */
@Composable
fun Toolbar(
    songTitle: String?,
    songArtist: String?,
    songThumbnailUrl: String?,
    isPlaying: Boolean,
    status: PlaybackStatus?,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    volumePercent: Int,
    onVolumeChange: (Int) -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenPlayer: () -> Unit,
    windowMaximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    /**
     * The window's own scope, handed down so this row can be the drag area:
     * [WindowDraggableArea] is an extension on it, and this is the one place
     * a page needs the frame.
     */
    windowScope: WindowScope,
    modifier: Modifier = Modifier,
) {
    with(windowScope) {
        WindowDraggableArea(modifier = modifier) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TOOLBAR_HEIGHT)
                    // The title bar's own gesture, on the chrome that replaced
                    // it: a double-click maximizes or restores. The buttons and
                    // the LCD consume their own clicks, so the shortcut belongs
                    // to the empty chrome between them and never fires by
                    // accident on a control.
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onToggleMaximize() })
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
        // Back and forward. A desktop navigation has both, the same two glyphs
        // every other desktop app reaches for; disabled rather than hidden so
        // the row's rhythm does not shift when a stack empties.
        val backDescription = stringResource(Res.string.back)
        val forwardDescription = stringResource(Res.string.forward)
        IconButton(onClick = onBack, enabled = canGoBack, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = backDescription,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onForward, enabled = canGoForward, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = forwardDescription,
                modifier = Modifier.size(22.dp),
            )
        }

        // The transport. Five controls, the reference app's order: shuffle
        // before the skips, repeat after — both toggles that live in the
        // full-screen player on a phone, but a desktop toolbar is where they
        // are always within reach. The tint carries each state: a dead
        // transport sits at a third strength, a toggle that is off at two
        // thirds, an active one full.
        val hasTrack = songTitle != null
        val transportTint = MaterialTheme.colorScheme.onBackground
        val dim = transportTint.copy(alpha = 0.35f)
        val off = transportTint.copy(alpha = 0.62f)

        IconButton(onClick = onToggleShuffle, modifier = Modifier.size(36.dp)) {
            Icon(
                BitChordIcons.Shuffle,
                contentDescription = stringResource(
                    if (shuffleEnabled) Res.string.shuffle_on else Res.string.shuffle_off,
                ),
                tint = if (shuffleEnabled) transportTint else off,
                modifier = Modifier.size(17.dp),
            )
        }
        IconButton(onClick = onPrevious, enabled = hasTrack, modifier = Modifier.size(36.dp)) {
            Icon(
                painterResource(Res.drawable.ic_player_previous),
                contentDescription = null,
                tint = if (hasTrack) transportTint else dim,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = onPlayPause, enabled = hasTrack, modifier = Modifier.size(40.dp)) {
            Icon(
                painterResource(if (isPlaying) Res.drawable.ic_player_pause else Res.drawable.ic_player_play),
                contentDescription = stringResource(if (isPlaying) Res.string.pause else Res.string.play),
                tint = if (hasTrack) transportTint else dim,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onNext, enabled = hasTrack, modifier = Modifier.size(36.dp)) {
            Icon(
                painterResource(Res.drawable.ic_player_next),
                contentDescription = null,
                tint = if (hasTrack) transportTint else dim,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = onCycleRepeat, enabled = hasTrack, modifier = Modifier.size(36.dp)) {
            val repeating = repeatMode != RepeatMode.OFF
            Icon(
                BitChordIcons.Repeat,
                contentDescription = stringResource(
                    when (repeatMode) {
                        RepeatMode.ONE -> Res.string.repeat_one
                        RepeatMode.ALL -> Res.string.repeat_all
                        else -> Res.string.repeat_off
                    },
                ),
                tint = if (repeating) transportTint else off,
                modifier = Modifier.size(17.dp),
            )
        }

        Spacer(Modifier.weight(0.5f))

        NowPlayingDisplay(
            title = songTitle,
            artist = songArtist,
            thumbnailUrl = songThumbnailUrl,
            status = status,
            onOpen = onOpenPlayer,
            modifier = Modifier.weight(1.6f, fill = false).widthIn(max = LCD_MAX_WIDTH),
        )

        Spacer(Modifier.weight(0.5f))

        VolumeControl(
            volumePercent = volumePercent,
            onVolumeChange = onVolumeChange,
        )

        // The window's own three, flush at the frame's right edge: the
        // undecorated window has no title bar, so they live at the row's end
        // and the close button calls the same path as the frame's close.
        Spacer(Modifier.width(10.dp))
        WindowButtons(
            maximized = windowMaximized,
            onMinimize = onMinimize,
            onToggleMaximize = onToggleMaximize,
            onClose = onClose,
        )
                }
        }
    }
}
}

/**
 * Minimize, maximize/restore, close — the window's own chrome, drawn to sit
 * on this dark toolbar the way the native buttons never did. The hover fill
 * is a plain rectangle spanning the toolbar's full height, the shape the
 * platform's own buttons use, rather than a pill floating in the row.
 */
@Composable
private fun WindowButtons(
    maximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    WindowButton(Icons.Rounded.Remove, onMinimize)
    WindowButton(if (maximized) Icons.Rounded.FilterNone else Icons.Rounded.CropSquare, onToggleMaximize)
    WindowButton(Icons.Rounded.Close, onClose)
}

@Composable
private fun WindowButton(icon: ImageVector, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .width(44.dp)
            .fillMaxHeight()
            .background(
                if (hovered) {
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.09f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * The toolbar's now-playing display, as the floating panel the reference
 * app's toolbar carries: a rounded well in the page's own colour holding the
 * artwork and the two scrolling lines — and on a tap, the full-screen player,
 * which is the only way this build opens it since the mini player it replaced
 * is gone.
 *
 * With no track it carries the wordmark, so the empty toolbar still says what
 * app it is; while a collection is being opened it carries the status text —
 * the moment between tap and sound that used to get its own floating pill.
 */
@Composable
private fun NowPlayingDisplay(
    title: String?,
    artist: String?,
    thumbnailUrl: String?,
    status: PlaybackStatus?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onOpen)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        when {
            title != null -> {
                AsyncImage(
                    model = thumbnailUrl?.artworkAt(ROW_ART_PX),
                    contentDescription = null,
                    modifier = Modifier
                        .size(26.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .thumbnailBorder(RoundedCornerShape(4.dp)),
                )
                Column(Modifier.widthIn(max = LCD_MAX_WIDTH - 80.dp)) {
                    MarqueeText(
                        text = title,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    artist?.let {
                        MarqueeText(
                            text = it,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.W500,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            status != null -> Text(
                statusText(status),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            else -> Icon(
                painterResource(Res.drawable.ic_logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(width = 84.dp, height = 22.dp),
            )
        }
    }
}

/** Speaker-to-speaker volume, the way the full-screen player's row is built. */
@Composable
private fun VolumeControl(
    volumePercent: Int,
    onVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.VolumeDown,
            contentDescription = stringResource(Res.string.volume),
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
        ThinSlider(
            value = volumePercent / 100f,
            onValueChange = { onVolumeChange((it * 100).toInt()) },
            modifier = Modifier
                .width(90.dp)
                .padding(horizontal = 6.dp),
        )
        Icon(
            Icons.Rounded.VolumeUp,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** The toolbar's corner of the status wording — the other half lives in the full-screen player. */
@Composable
fun statusText(status: PlaybackStatus): String = when (status) {
    PlaybackStatus.Resolving -> stringResource(Res.string.status_resolving)
    is PlaybackStatus.Opening -> stringResource(Res.string.status_opening, status.label)
    is PlaybackStatus.NothingPlayable ->
        stringResource(Res.string.status_nothing_playable, status.label)
    is PlaybackStatus.ResolveFailed -> stringResource(Res.string.status_resolve_failed)
    is PlaybackStatus.EngineError -> stringResource(Res.string.status_engine_error, status.message)
}
