package com.music.bitchord.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.MouseInfo
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
     * The frame itself: the drag gesture below moves it, and it is the one
     * thing a page needs of the window.
     */
    window: java.awt.Window,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TOOLBAR_HEIGHT)
            // The title bar's two gestures, on the chrome that replaced it:
            // double-click maximizes or restores, a press-and-drag moves the
            // window. The buttons and the LCD consume their own clicks, so
            // neither fires by accident on a control.
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onToggleMaximize() })
            }
            // Keyed on the maximised flag too: the restore-on-drag below reads
            // it, and a lambda keyed only on `window` would go on holding the
            // value from before the double-click that maximised the window.
            .pointerInput(window, windowMaximized) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A control that claimed the press owns the gesture; the
                    // window moving under it is not what was asked for.
                    if (down.isConsumed) return@awaitEachGesture
                    val startMouse = MouseInfo.getPointerInfo().location
                        ?: return@awaitEachGesture
                    val startWindow = window.location
                    var moved = false
                    // Distance summed across events, not per event: a mouse
                    // delivers many small moves, and no single one of them
                    // crosses the slop a hand-drawn drag must.
                    var travel = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.firstOrNull { it.pressed }
                        if (pressed == null) break
                        // A child that claims the drag — the volume slider's
                        // pull — ends ours.
                        if (pressed.isConsumed) break
                        if (!moved) {
                            travel += pressed.positionChange().getDistance()
                            if (travel > viewConfiguration.touchSlop) {
                                moved = true
                                // A maximised frame is not dragged — it is
                                // restored, the way dragging a title bar off a
                                // maximised window restores it. The restore
                                // lands at bounds this gesture cannot know, so
                                // the drag ends here and the next one moves the
                                // window.
                                if (windowMaximized) {
                                    onToggleMaximize()
                                    pressed.consume()
                                    break
                                }
                            }
                        }
                        if (moved) {
                            // By screen coordinates, never by deltas: the
                            // window moving under the pointer feeds back
                            // into the next position otherwise.
                            val now = MouseInfo.getPointerInfo().location ?: break
                            window.setLocation(
                                startWindow.x + now.x - startMouse.x,
                                startWindow.y + now.y - startMouse.y,
                            )
                            pressed.consume()
                        }
                    }
                }
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

        // The LCD fills its whole share between the two weighted spacers —
        // a wide panel, as the reference draws it. A fill=false weight here
        // was the bug that pushed the volume and the window buttons left:
        // an idle panel measures its content only, and the unused share
        // leaks past everything placed after it.
        NowPlayingDisplay(
            title = songTitle,
            artist = songArtist,
            thumbnailUrl = songThumbnailUrl,
            status = status,
            onOpen = onOpenPlayer,
            modifier = Modifier.weight(1.6f).height(38.dp),
        )

        Spacer(Modifier.weight(0.5f))

        VolumeControl(
            volumePercent = volumePercent,
            onVolumeChange = onVolumeChange,
        )
        }

        // The window's own three, flush at the frame's right edge — outside
        // the padded row above, so nothing stands between them and the frame.
        WindowButtons(
            maximized = windowMaximized,
            onMinimize = onMinimize,
            onToggleMaximize = onToggleMaximize,
            onClose = onClose,
        )
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
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.background)
            // The tap is handled by hand rather than by `clickable` because
            // the LCD sits in the drag area: a clickable consumes the press,
            // and a consumed press is exactly what the toolbar's drag
            // gesture stands down on. This one consumes nothing on the way
            // down — so a press-drag here moves the window — and claims only
            // the release of a genuine tap, which also keeps the toolbar's
            // double-click detector from reading a single tap as its first
            // half.
            .pointerInput(onOpen) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: return@awaitEachGesture
                        if (!change.pressed) {
                            change.consume()
                            onOpen()
                            return@awaitEachGesture
                        }
                        // The window drag carried the pointer away — past the
                        // slop, or claimed outright — so this was no tap.
                        if (change.isConsumed ||
                            change.positionChange().getDistance() > viewConfiguration.touchSlop
                        ) {
                            return@awaitEachGesture
                        }
                    }
                }
            }
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        // Idle, the wordmark sits centred in the panel the way the reference
        // app's does; playing, the artwork leads and the lines follow it.
        horizontalArrangement = if (title == null) {
            Arrangement.Center
        } else {
            Arrangement.spacedBy(9.dp)
        },
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
                // Weighted, so the marquees are bounded by the panel the
                // caller allocated and actually scroll when they must.
                Column(Modifier.weight(1f)) {
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
