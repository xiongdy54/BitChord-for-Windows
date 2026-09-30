// Ported from app/src/main/java/com/music/bitchord/ui/player/PlayerControls.kt.
//
// Four feature families the desktop does not ship went with their members —
// lyrics, AutoPlay, audio output, Listen Together (and with them the party pill,
// the output caption and the controls-lock read) — along with the Android audio
// stack (`AudioFormat`) and the cross-process / lossless-racing `NerdStats`
// snapshot, neither of which has a counterpart on this platform. What is left is
// the app's own layout, sizes, gesture gates and wording.
//
// One mechanical difference from upstream: the app declares the size constants
// `private`, and a test living in another file cannot read a private top-level
// declaration, so here they are `internal`. Values, names and units verbatim —
// `PlayerGeometryTest` is what keeps them that way.
package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.graphicsLayer
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.*

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.SystemClock
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.RepeatMode
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * How long the shuffle glyph ignores further taps after one lands.
 *
 * Toggling shuffle replaces the upcoming stretch of the live queue. A second
 * tap while that command is crossing the session boundary could otherwise ask
 * to undo work that has not landed yet. One tap is all a toggle can usefully
 * mean in that window, so the rest are dropped rather than queued behind it.
 *
 * `internal`, not the app's `private`, only so the geometry test can pin it.
 */
internal const val SHUFFLE_TAP_WINDOW_MS = 400L

/**
 * "Playing from …" or the radio station — what the player is
 * playing *out of*, in the words the caption uses.
 *
 * Upstream this also answered "Played by …" when a Listen Together host had
 * started the track; that family does not ship here, so there is one less
 * branch and one less parameter.
 */
@Composable
internal fun playbackOriginText(song: Song): String =
    song.radioName?.let {
        stringResource(Res.string.playing_radio, it)
    } ?: stringResource(
        Res.string.playing_from,
        song.playbackSource ?: song.albumName ?: stringResource(Res.string.queue),
    )

/**
 * The small caption naming what the player is playing out of — see
 * [playbackOriginText]. Tappable, back to that place.
 */
@Composable
internal fun PlaybackOriginCaption(
    text: String,
    onClick: () -> Unit,
    textAlign: TextAlign,
    /** Inside the touch target, so it widens what a finger can hit. */
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.55f),
                offset = Offset(0f, 1f),
                blurRadius = 4f,
            ),
        ),
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(contentPadding),
    )
}

/**
 * The seek bar, with elapsed and remaining either side under it and
 * [centerLabel] — the quality badge — between them.
 */
@Composable
internal fun PlayerScrubber(
    /**
     * Where the handle is, read in here so the playhead's tick recomposes the
     * bar and its two times rather than the player the bar sits in.
     */
    shown: () -> Float,
    durationMs: Long,
    /** A version switch's wait, drawn along the bar — see [ThinSlider.loading]. */
    loading: Boolean,
    transitionWindow: ClosedFloatingPointRange<Float>?,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    centerLabel: @Composable BoxScope.() -> Unit = {},
) {
    val shown = shown()
    Column(Modifier.fillMaxWidth()) {
        ThinSlider(
            value = shown,
            onValueChange = onScrub,
            onValueChangeFinished = onScrubFinished,
            loading = loading,
            transitionWindow = transitionWindow,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // The slider's touch target extends well past the drawn bar,
                // so pull the labels back up under it.
                .offset(y = (-9).dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatTime((shown * durationMs).toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                )
                Text(
                    text = "-" + formatTime(durationMs - (shown * durationMs).toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
            // Pinned to the box's own centre rather than squeezed into the gap
            // between the two timestamps: that gap's width changes by a digit's
            // worth every time a minute rolls over.
            centerLabel()
        }
    }
}

/**
 * The quality badge between the timestamps.
 *
 * Upstream this asked the settings object which rung a metered connection
 * allowed and whether a module was still racing YouTube for a lossless copy.
 * A desktop is one machine on one connection with no second source to race,
 * so there is one rung — [AppSettings.effectiveAudioQuality] — and the badge
 * reports only what this process actually recorded.
 */
@Composable
internal fun PlaybackQualityLabel(
    song: Song,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    // Collected rather than read off `effectiveAudioQuality` so the badge follows
    // a quality change; that property is this same flow's accessor (:45).
    val effectiveQuality by AppSettings.audioQuality.collectAsState()
    LosslessOrStats(
        isLoading = isLoading,
        losslessRequested = effectiveQuality == AudioQuality.LOSSLESS,
        pickedKbps = NerdStats.pickedKbps(song.videoId),
        modifier = modifier,
    )
}

/**
 * Stats for nerds, on the foot of the sleeve: what the resolver settled on for
 * this track.
 *
 * Upstream drew two lines here — the measured stream, and under it what
 * Automix's analysis was doing. The second needed the smart-fade switch, its
 * per-track analysis state and the party's own "we don't mix" exception, none
 * of which exist on this branch yet; it is simply absent rather than stubbed.
 *
 * What remains is a short " · "-joined line in the upstream's own style — a
 * figure that is not known is dropped, never filled in — and it is deliberately
 * quiet until the resolver has recorded something, because before that there is
 * nothing to report but a preference.
 *
 * Read the fields for what they are: the first is the ceiling this app *asked*
 * the resolver for, the rest are what the resolver recorded. Neither is a
 * statement about the machine's audio path or about the format of the bytes
 * that reach it, which nothing here can observe.
 */
@Composable
internal fun SleeveNerdStats(song: Song, modifier: Modifier = Modifier) {
    val effectiveQuality by AppSettings.audioQuality.collectAsState()
    val kbps = NerdStats.pickedKbps(song.videoId)
    val source = NerdStats.pickedSource(song.videoId)
    if (kbps == null && source == null) return
    // A plain white line reads fine over the usual dark tile, but a light
    // stretch of an animated cover — sky, snow, a pale sleeve — washes it out
    // entirely. The shadow costs nothing on a dark background and is what
    // keeps it legible on a bright one.
    val nerdStyle = MaterialTheme.typography.labelSmall.copy(
        shadow = Shadow(
            color = Color.Black.copy(alpha = 0.55f),
            offset = Offset(0f, 1f),
            blurRadius = 4f,
        ),
    )
    val line = listOfNotNull(
        effectiveQuality.label,
        kbps?.let { "$it kbps" },
        source,
    ).joinToString(" · ")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Text(
            text = line,
            style = nerdStyle,
            color = Color.White.copy(alpha = 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Previous, play/pause, next.
 *
 * [compact] is the landscape player on a phone, where the portrait player's
 * 100dp play target is a third of the window's height on its own.
 */
@Composable
internal fun TransportRow(
    isPlaying: Boolean,
    /** While the stream resolves and buffers, the play glyph would be a lie. */
    isLoading: Boolean,
    /**
     * Lit whenever back has something to do — a track to step to, or enough
     * elapsed to restart this one. Faded and inert rather than removed at the
     * end of a queue: the transport keeps its shape either way.
     *
     * Upstream this was also the channel a Listen Together host used to take
     * the controls from this device; that family does not ship here, so the
     * queue's own ends are the only thing that dims these two glyphs.
     */
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    compact: Boolean = false,
) {
    val playSize = if (compact) 58.dp else 74.dp
    val playTouch = if (compact) 76.dp else 92.dp
    val skipSize = if (compact) 44.dp else PLAYER_SKIP_ICON_SIZE
    Row(
        modifier = Modifier.fillMaxWidth(),
        // SpaceAround, not SpaceEvenly: the outer margins take half a gap, so
        // the three buttons spread a little further apart from each other.
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportGlyph(
            icon = Res.drawable.ic_player_previous,
            contentDescription = stringResource(Res.string.widget_previous),
            size = skipSize,
            touchSize = PLAYER_SKIP_TOUCH_SIZE,
            heightScale = PLAYER_SKIP_HEIGHT_SCALE,
            onClick = onPrevious,
            enabled = previousEnabled,
            haptic = Haptic.SkipPrevious,
        )
        if (isLoading) {
            // Same footprint as the play/pause target — a smaller box here
            // would shunt everything below it on every load.
            Box(Modifier.size(playTouch), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(if (compact) 30.dp else 38.dp),
                )
            }
        } else {
            TransportGlyph(
                icon = if (isPlaying) Res.drawable.ic_player_pause else Res.drawable.ic_player_play,
                contentDescription = stringResource(if (isPlaying) Res.string.pause else Res.string.play),
                size = playSize,
                touchSize = playTouch,
                onClick = onPlayPause,
                haptic = if (isPlaying) Haptic.Pause else Haptic.Resume,
            )
        }
        TransportGlyph(
            icon = Res.drawable.ic_player_next,
            contentDescription = stringResource(Res.string.widget_next),
            size = skipSize,
            touchSize = PLAYER_SKIP_TOUCH_SIZE,
            heightScale = PLAYER_SKIP_HEIGHT_SCALE,
            onClick = onNext,
            enabled = nextEnabled,
            haptic = Haptic.SkipNext,
        )
    }
}

/** ThinSlider's fixed touch target at the volume bar's size: 10dp + 22dp. */
internal val VOLUME_ROW_HEIGHT = 32.dp

/** The volume bar between its two speaker glyphs. */
@Composable
internal fun VolumeRow(
    /**
     * Read inside the row, so the level's tween recomposes the row alone rather
     * than the whole player around it.
     */
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.VolumeDown,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        ThinSlider(
            value = value(),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            idleHeight = 6.dp,
            activeHeight = 10.dp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The capsule and the queue — the row both layouts end on.
 *
 * Upstream this row was lyrics, the capsule, and the queue, and the capsule
 * itself swapped between the three playback modes and the output/party pair
 * while the queue came up and went down. Lyrics is a later slice, and the
 * output and party pair with it never ships here, so what is left is one
 * capsule that always holds the playback modes and one glyph beside it.
 *
 * That is a visible shape change rather than a tidied-up one: with a single
 * state to render, the swap and the wider-of-two-capsules sizing it was inside
 * are gone, and the capsule sits against the row's leading edge instead of at
 * the centre. See [pillWidth] for the width the inset is computed from.
 */
@Composable
internal fun PlayerActionRow(
    queueOpen: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onToggleQueue: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Sized for the capsule this row actually holds — shuffle and repeat,
        // the two playback modes that ship — plus the queue glyph beside it.
        // Upstream this was the wider of two capsules rather than the one on
        // screen, because a value that changed as they swapped would have slid
        // the glyphs at either end with it.
        val widestRow = BOTTOM_ACTION_SIZE + pillWidth(2)
        val edgeInset = ((maxWidth - widestRow) / 4).coerceAtLeast(0.dp)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = edgeInset),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The two playback modes that ship, at the narrower of upstream's
            // two segment spacings — the one the app tuned for density, not the
            // wider one the output capsule used, which does not come over. See
            // [PILL_SEGMENT_WIDTH_TRIPLE].
            Pill {
                PillSegment(
                    icon = BitChordIcons.Shuffle,
                    contentDescription = stringResource(
                        if (shuffleEnabled) Res.string.shuffle_on else Res.string.shuffle_off,
                    ),
                    onClick = onToggleShuffle,
                    highlighted = shuffleEnabled,
                    haptic = if (shuffleEnabled) Haptic.ToggleOff else Haptic.ToggleOn,
                    tapWindowMs = SHUFFLE_TAP_WINDOW_MS,
                    width = PILL_SEGMENT_WIDTH_TRIPLE,
                )
                PillDivider()
                PillSegment(
                    icon = if (repeatMode == RepeatMode.ONE) null else BitChordIcons.Repeat,
                    label = if (repeatMode == RepeatMode.ONE) "1" else null,
                    contentDescription = when (repeatMode) {
                        RepeatMode.ONE -> stringResource(Res.string.repeat_one)
                        RepeatMode.ALL -> stringResource(Res.string.repeat_all)
                        else -> stringResource(Res.string.repeat_off)
                    },
                    onClick = onCycleRepeat,
                    highlighted = repeatMode != RepeatMode.OFF,
                    // Three states, so the buzz tracks the edges of the
                    // cycle: leaving off rises, returning to off falls, and
                    // the step between the two repeat modes is just
                    // a selection.
                    haptic = when (repeatMode) {
                        RepeatMode.OFF -> Haptic.ToggleOn
                        RepeatMode.ONE -> Haptic.ToggleOff
                        else -> Haptic.Select
                    },
                    width = PILL_SEGMENT_WIDTH_TRIPLE,
                )
            }
            BottomGlyph(
                icon = BitChordIcons.Queue,
                contentDescription = stringResource(Res.string.up_next),
                onClick = onToggleQueue,
                highlighted = queueOpen,
                haptic = if (queueOpen) Haptic.Tap else Haptic.Expand,
            )
        }
    }
}

/**
 * Translucent circular button used for the track menu and the like control.
 *
 * [active] brightens the disc rather than only the glyph: this sits on album
 * artwork of any colour, and a white icon on a white-ish sleeve has no tint
 * change left to make. The filled heart carries the state as a shape too —
 * see [BitChordIcons.HeartFilled].
 */
@Composable
internal fun CircleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
    haptic: Haptic = Haptic.Tap,
) {
    val haptics = rememberHaptics()
    val discAlpha by animateFloatAsState(
        targetValue = if (active) 0.34f else 0.18f,
        label = "glyphDisc",
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = discAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.play(haptic)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = icon,
            animationSpec = tween(durationMillis = 180),
            label = "playerMenuGlyph",
        ) { glyph ->
            Icon(
                imageVector = glyph,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/**
 * Transport / bottom glyphs. The circular clip belongs on the touch target,
 * never on the [Icon] — clipping the icon itself shaves the corners off wide
 * glyphs like fast-forward and the queue list.
 */
@Composable
private fun TransportGlyph(
    icon: DrawableResource,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    touchSize: androidx.compose.ui.unit.Dp = size,
    onClick: () -> Unit,
    enabled: Boolean = true,
    haptic: Haptic = Haptic.Tap,
    /** Vertical squash of the glyph alone; its width and touch box are untouched. */
    heightScale: Float = 1f,
) {
    val haptics = rememberHaptics()
    // Faded rather than hidden: the row keeps its shape at the ends of a queue.
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.3f,
        label = "transportAlpha",
    )
    Box(
        modifier = Modifier
            .size(touchSize)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) {
                haptics.play(haptic)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = alpha),
            modifier = Modifier
                .size(size)
                .then(if (heightScale != 1f) Modifier.graphicsLayer { scaleY = heightScale } else Modifier),
        )
    }
}

/** The skip glyphs' width, and their touch box, beside the larger play button. */
internal val PLAYER_SKIP_ICON_SIZE = 53.dp
internal val PLAYER_SKIP_TOUCH_SIZE = 53.dp

/**
 * The skip glyphs are drawn a little flatter than they are wide, so the pair
 * reads lower and longer next to the play button without losing any width.
 */
internal const val PLAYER_SKIP_HEIGHT_SCALE = 0.85f

internal val BOTTOM_ACTION_SIZE = 44.dp

/**
 * Segment width for the Shuffle/Repeat capsule.
 *
 * Upstream this constant existed alongside a two-up `PILL_SEGMENT_WIDTH` of
 * 64dp that the output capsule used, and the KDoc's reason for the narrower
 * number was that 64dp of spacing "left each glyph with room the eye read as
 * empty even at two". With the output capsule gone this is the only segment
 * width, and the capsule it sizes holds two glyphs — which is the case it was
 * tuned for.
 */
internal val PILL_SEGMENT_WIDTH_TRIPLE = 52.dp

/** What a segment's glyph is drawn at when it has no optical quirk to correct. */
internal val PILL_ICON_SIZE = 24.dp

/** How wide a capsule of [segments] comes out, dividers included. */
private fun pillWidth(segments: Int): Dp =
    PILL_SEGMENT_WIDTH_TRIPLE * segments + 1.dp * (segments - 1)

/**
 * A row of controls joined into one capsule.
 *
 * The join is a hairline rather than a gap, which is what makes several
 * controls read as a single object — the shape the player uses for a set of
 * choices that all answer the same question. Upstream there were two such
 * capsules, here one.
 */
@Composable
private fun Pill(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .height(BOTTOM_ACTION_SIZE)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun PillDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = 0.20f)),
    )
}

/**
 * One control inside a [Pill] — [BottomGlyph]'s twin, squared off.
 *
 * Same behaviour down to the tap window, and deliberately not the same
 * composable: a glyph's highlight is a circle sized to itself, and a segment's
 * has to fill its share of the capsule edge to edge or the join stops reading
 * as one.
 *
 * [trailingLabel] and [loading] are kept as upstream declared them. The first had
 * exactly one caller — the party's member count, a family that does not ship here
 * — and the second had none even in the app, so both branches are reachable but
 * unused rather than stubbed out.
 */
@Composable
private fun PillSegment(
    contentDescription: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    iconSize: Dp = PILL_ICON_SIZE,
    label: String? = null,
    trailingLabel: String? = null,
    highlighted: Boolean = false,
    haptic: Haptic = Haptic.Tap,
    loading: Boolean = false,
    /** See [BottomGlyph], where the same window means the same thing. */
    tapWindowMs: Long = 0L,
    /** Per-segment override — see [PlayerActionRow]'s capsule. */
    width: Dp = PILL_SEGMENT_WIDTH_TRIPLE,
) {
    val haptics = rememberHaptics()
    val lastTap = remember { mutableLongStateOf(-tapWindowMs) }
    Box(
        modifier = Modifier
            .width(width)
            .height(BOTTOM_ACTION_SIZE)
            .background(if (highlighted) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !loading,
            ) {
                val now = SystemClock.uptimeMillis()
                if (now - lastTap.longValue >= tapWindowMs) {
                    lastTap.longValue = now
                    haptics.play(haptic)
                    onClick()
                }
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val tint = Color.White.copy(alpha = if (highlighted) 1f else 0.75f)
        Crossfade(
            targetState = loading,
            animationSpec = tween(durationMillis = 200),
            label = "pillSegmentLoading",
        ) { isLoading ->
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else if (icon != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Crossfade(
                        targetState = icon,
                        animationSpec = tween(durationMillis = 200),
                        label = "pillSegmentIcon",
                    ) { currentIcon ->
                        Icon(
                            imageVector = currentIcon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(iconSize),
                        )
                    }
                    if (trailingLabel != null) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = trailingLabel,
                            color = tint,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            } else if (label != null) {
                Text(
                    text = label,
                    color = tint,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun BottomGlyph(
    icon: ImageVector?,
    contentDescription: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    haptic: Haptic = Haptic.Tap,
    label: String? = null,
    /**
     * Shortest gap between taps that both reach [onClick]. A tap inside the
     * window of the last one is dropped whole — haptic included, so a swallowed
     * tap doesn't buzz as though something happened. The default lets every tap
     * through: only the glyphs whose work is too heavy to repeat at finger speed
     * ask for a window.
     */
    tapWindowMs: Long = 0L,
) {
    val haptics = rememberHaptics()
    // Read only from the click handler, never during composition, so writing it
    // costs no recomposition. Starts a full window in the past so the first tap
    // is never the one that gets swallowed.
    val lastTap = remember { mutableLongStateOf(-tapWindowMs) }
    Box(
        modifier = Modifier
            .size(BOTTOM_ACTION_SIZE)
            .clip(CircleShape)
            .background(
                if (highlighted) Color.White.copy(alpha = 0.20f) else Color.Transparent,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                val now = SystemClock.uptimeMillis()
                if (now - lastTap.longValue >= tapWindowMs) {
                    lastTap.longValue = now
                    haptics.play(haptic)
                    onClick()
                }
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val tint = Color.White.copy(alpha = if (highlighted) 1f else 0.75f)
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(26.dp),
            )
        } else if (label != null) {
            Text(
                text = label,
                color = tint,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** A credit that links somewhere, when [browseId] is known. */
internal fun Modifier.opensPage(browseId: String?, onOpen: (String) -> Unit): Modifier =
    if (browseId == null) {
        this
    } else {
        clip(RoundedCornerShape(6.dp)).clickable { onOpen(browseId) }
    }

/** How fast the title/artist marquee crawls — unhurried, not a ticker. */
internal const val MARQUEE_DP_PER_SEC = 26f

/** Clear air between the tail of the line and the copy chasing it round. */
internal val MARQUEE_GAP = 48.dp

/** How long a line sits back at its start before the next pass — the "5 seconds" rest. */
internal const val MARQUEE_REST_MS = 5_000L

/** Artist's head start is ceded to the title when both are scrolling, so they don't start as one block. */
internal const val MARQUEE_ARTIST_STAGGER_MS = 3_000L

/**
 * A single line of text that scrolls in place, only when it is too long for
 * [modifier]'s width to show in full.
 *
 * Idle text never animates — the scroll only kicks in once measurement proves
 * an ellipsis would otherwise be needed. When it does, the line is drawn twice
 * with [MARQUEE_GAP] between the copies and the pair is crawled leftwards by
 * exactly one copy-plus-gap: the trailing copy chases the leading one in from
 * the right and lands precisely where it started, so the offset reset at the
 * end of the pass falls under a copy already in position and cannot be seen.
 * The line therefore only ever travels one way — right to left, round and back
 * to its resting place — rather than bouncing back the way it came.
 *
 * A pass is: wait [startDelayMillis] (used to stagger the artist line behind
 * the title), crawl one full loop, then rest [MARQUEE_REST_MS] at the start
 * before going again. [onOverflowChange] reports whether this line is scrolling
 * at all, so a sibling line can decide whether it needs to stagger behind it.
 *
 * With [enabled] false the line is a plain ellipsised one — no copies, no
 * animation, nothing left running off screen.
 */
@Composable
internal fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    startDelayMillis: Long = 0L,
    leading: (@Composable () -> Unit)? = null,
    onOverflowChange: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        if (!enabled) {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            return@Row
        }
        BoxWithConstraints(Modifier.weight(1f, fill = false).clipToBounds()) {
            val maxWidthPx = constraints.maxWidth
            val layout = remember(text, style, maxWidthPx) {
                textMeasurer.measure(text = text, style = style, maxLines = 1, softWrap = false)
            }
            val overflowing = layout.size.width > maxWidthPx
            LaunchedEffect(overflowing) { onOverflowChange(overflowing) }

            // One whole copy plus the gap behind it: that is the distance at
            // which the second copy is sitting exactly where the first was.
            val travelPx = if (overflowing) {
                layout.size.width + with(density) { MARQUEE_GAP.roundToPx() }
            } else {
                0
            }

            val offsetX = remember { Animatable(0f) }
            LaunchedEffect(text, travelPx, startDelayMillis) {
                offsetX.snapTo(0f)
                if (travelPx <= 0) return@LaunchedEffect
                val pxPerMs = with(density) { MARQUEE_DP_PER_SEC.dp.toPx() } / 1000f
                val scrollMs = (travelPx / pxPerMs).roundToInt().coerceAtLeast(400)
                delay(startDelayMillis)
                while (true) {
                    offsetX.animateTo(-travelPx.toFloat(), tween(scrollMs, easing = LinearEasing))
                    // Invisible: the trailing copy has arrived at the leading
                    // one's starting mark, so the line is already back where
                    // this puts it.
                    offsetX.snapTo(0f)
                    delay(MARQUEE_REST_MS)
                }
            }

            Row(
                // Measured unbounded so the copies actually lay out at their
                // full width, wider than the clipped box around them — bounded,
                // the text is truncated during its own measurement and sliding
                // it sideways just moves an already-cut string.
                modifier = Modifier
                    .wrapContentWidth(align = Alignment.Start, unbounded = true)
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MarqueeLine(text = text, style = style, color = color)
                if (overflowing) {
                    Spacer(Modifier.width(MARQUEE_GAP))
                    MarqueeLine(text = text, style = style, color = color)
                }
            }
        }
    }
}

/** One copy of a marquee's line, laid out at its full width rather than clipped. */
@Composable
private fun MarqueeLine(text: String, style: TextStyle, color: Color) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

/** The small "E" pill for explicit tracks, kept outside the scrolling text. */
@Composable
internal fun ExplicitBadge(color: Color) {
    Text(
        text = "E",
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.72f), RoundedCornerShape(2.dp))
            .padding(horizontal = 3.dp),
    )
}

/**
 * Measure a child wider than its slot by [gutter] on each side and place it back
 * over that margin, still reporting the original width to the parent.
 *
 * The lists are the only things in the player you can scroll, and the side
 * padding left a strip of bare sheet down each edge. A finger that drifted into
 * one scrolled nothing and closed the player instead. Matching content padding
 * puts every row back exactly where it was drawn, so this is invisible.
 */
internal fun Modifier.bleedHorizontally(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = gutter.roundToPx() * 2
    val widened = if (constraints.hasBoundedWidth) {
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = constraints.maxWidth + extra,
        )
    } else {
        constraints
    }
    val placeable = measurable.measure(widened)
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) {
        placeable.place(-(placeable.width - width) / 2, 0)
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(Locale.ROOT, minutes, seconds)
}

/**
 * The gap between the two timestamps under the seek bar: the rate of the stream
 * the resolver picked, once it has picked one, and the wait for it while it has
 * not.
 *
 * Upstream this badge had six branches and read every one of them off a measured
 * stream: lossless, Hi-Res lossless, Dolby Atmos, a module's 320kbps tier, the
 * data-saver rung, the medium one — with a highlight sweeping across the two
 * that had been asked for *and confirmed*. The measurement came in through the
 * platform's decoder callback and a cross-process snapshot of it; this process
 * has neither, so nothing here can say a format was verified. Which is also why
 * no branch shimmers: shimmer is the word upstream used for "confirmed", and
 * what `data/innertube/StreamResolver.kt:274` records is the figure the *source*
 * stated for the stream it chose — source metadata, not a decoder's report and
 * not the machine's path.
 *
 * [pickedKbps] is read at composition rather than collected — the resolver's
 * record is a lookup, not a flow. It lands while the badge is recomposing for
 * the load that fetched it, which is the same moment it would have landed any
 * other way.
 */
@Composable
private fun LosslessOrStats(
    isLoading: Boolean,
    losslessRequested: Boolean,
    pickedKbps: Int?,
    modifier: Modifier = Modifier,
) {
    when {
        // Still resolving, and nothing recorded for this track yet. A statement
        // of what the app is asking the resolver for, not of what has arrived —
        // which is why this one is not animated either.
        isLoading && losslessRequested && pickedKbps == null -> LosslessLabel(
            text = stringResource(Res.string.upgrading_quality),
            modifier = modifier,
        )
        // The rate the resolver picked for this stream. Named as a rate, because
        // a codec, a bit depth and a sample rate are all things the app's own
        // badge could claim from a decoder it does not have here.
        pickedKbps != null -> LosslessLabel(
            text = "$pickedKbps kbps",
            modifier = modifier,
        )
        else -> {}
    }
}

/** A quality glyph ahead of the status label. */
@Composable
private fun LosslessLabel(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Headphones,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + 1).sp,
            ),
            color = Color.White.copy(alpha = 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
