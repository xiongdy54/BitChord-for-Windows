package com.music.bitchord.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.EqualizerMode
import com.music.bitchord.playback.EqLayout
import com.music.bitchord.playback.EqualizerPreset
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.eq_preset_acoustic
import com.music.bitchord.desktop.resources.eq_preset_bass_boost
import com.music.bitchord.desktop.resources.eq_preset_bass_cut
import com.music.bitchord.desktop.resources.eq_preset_classical
import com.music.bitchord.desktop.resources.eq_preset_custom
import com.music.bitchord.desktop.resources.eq_preset_electronic
import com.music.bitchord.desktop.resources.eq_preset_flat
import com.music.bitchord.desktop.resources.eq_preset_hip_hop
import com.music.bitchord.desktop.resources.eq_preset_jazz
import com.music.bitchord.desktop.resources.eq_preset_late_night
import com.music.bitchord.desktop.resources.eq_preset_loudness
import com.music.bitchord.desktop.resources.eq_preset_rock
import com.music.bitchord.desktop.resources.eq_preset_small_speakers
import com.music.bitchord.desktop.resources.eq_preset_spoken_word
import com.music.bitchord.desktop.resources.eq_preset_treble_boost
import com.music.bitchord.desktop.resources.eq_preset_treble_cut
import com.music.bitchord.desktop.resources.eq_preset_vocal
import com.music.bitchord.desktop.resources.equalizer
import com.music.bitchord.desktop.resources.equalizer_bands
import com.music.bitchord.desktop.resources.equalizer_bands_footer
import com.music.bitchord.desktop.resources.equalizer_broad
import com.music.bitchord.desktop.resources.equalizer_broad_footer
import com.music.bitchord.desktop.resources.equalizer_contour
import com.music.bitchord.desktop.resources.equalizer_dynamic
import com.music.bitchord.desktop.resources.equalizer_focused
import com.music.bitchord.desktop.resources.equalizer_focused_footer
import com.music.bitchord.desktop.resources.equalizer_footer
import com.music.bitchord.desktop.resources.equalizer_manual
import com.music.bitchord.desktop.resources.equalizer_preset
import com.music.bitchord.desktop.resources.equalizer_reset
import com.music.bitchord.desktop.resources.equalizer_tilt
import com.music.bitchord.desktop.resources.equalizer_tone
import com.music.bitchord.desktop.resources.selected
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The app's own equaliser, as a dialog off the settings' playback group —
 * upstream `EqualizerScreen`'s 917-line page, gathered into the desktop's
 * dialog form. Same two shapes people already know how to read one in:
 *
 * **Dynamic** is a tone pad: one puck over a dot grid, tilting the spectrum
 * left-to-right and shaping the middle of it bottom-to-top, with Broad and
 * Focused choosing how wide each move is.
 *
 * **Manual** is the seven-band graphic equaliser, with a preset list over it
 * — the sheet upstream popped from the bottom of the phone's screen is an
 * in-dialog expansion here, the dialog being the smaller surface.
 *
 * Both drive the same curve model ([EqLayout]) — the tab is a way of
 * describing a curve rather than a different piece of audio machinery. What
 * upstream's page carried and this dialog does not: the balance slider and
 * the system-equaliser row (libvlc has neither a continuous trim nor a
 * device panel to hand over to) and the bitstream notice row (a VLC stream is
 * always decoded to PCM before the equalizer sees it).
 *
 * The settings flows are written here and read by
 * [com.music.bitchord.desktop.playback.PlayerController]'s collector, which
 * renders the curve and feeds the engines — the dialog is a surface, the
 * controller the actor, the same split every audio setting keeps.
 */
@Composable
fun EqualizerDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier.widthIn(max = 460.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            EqualizerPanel()
        }
    }
}

/**
 * The dialog's whole body, without the window around it — the screenshot
 * probe's way of showing it (a dialog inside the settings dialog renders in
 * its own native window, which the capture pipeline cannot see; the preview
 * renders this panel inline, the output picker's precedent).
 */
@Composable
fun EqualizerPanel() {
    val enabled by AppSettings.equalizerEnabled.collectAsState()
    val mode by AppSettings.equalizerMode.collectAsState()
    val toneX by AppSettings.equalizerToneX.collectAsState()
    val toneY by AppSettings.equalizerToneY.collectAsState()
    val focused by AppSettings.equalizerFocused.collectAsState()
    val bands by AppSettings.equalizerBands.collectAsState()
    val preset by AppSettings.equalizerPreset.collectAsState()

    val haptics = rememberHaptics()
    var pickingPreset by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            text = stringResource(Res.string.equalizer),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Text(
            text = stringResource(Res.string.equalizer_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The master switch, as the settings dialog's own rows are built: the
        // whole row answers, the switch on the end agrees with it. The
        // subtitle is upstream's own footer for the screen — what the
        // equaliser is and where it sits in the chain.
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    haptics.play(if (!enabled) Haptic.ToggleOn else Haptic.ToggleOff)
                    AppSettings.setEqualizerEnabled(!enabled)
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.equalizer),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(Res.string.equalizer_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    haptics.play(if (it) Haptic.ToggleOn else Haptic.ToggleOff)
                    AppSettings.setEqualizerEnabled(it)
                },
            )
        }

        // Off, the whole apparatus below reads as unavailable rather than
        // merely idle. Each control is told so itself rather than having its
        // touches swallowed by a layer above: the pad and the faders all claim
        // the gesture with `awaitFirstDown(requireUnconsumed = false)` — they
        // have to, or the scrolling dialog body would steal every vertical
        // drag — so an ancestor's consumption never reaches them.
        val settingsAlpha by animateFloatAsState(
            targetValue = if (enabled) 1f else 0.38f,
            animationSpec = tween(220),
            label = "equalizerEnabled",
        )
        Column(modifier = Modifier.graphicsLayer { alpha = settingsAlpha }) {
            GroupDivider()
            SegmentedControl(
                options = EqualizerMode.entries.map { it.localizedLabel() },
                selectedIndex = EqualizerMode.entries.indexOf(mode),
                onSelect = { AppSettings.setEqualizerMode(EqualizerMode.entries[it]) },
                enabled = enabled,
            )

            when (mode) {
                EqualizerMode.DYNAMIC -> {
                    GroupHeader(stringResource(Res.string.equalizer_tone))
                    TonePad(
                        x = toneX,
                        y = toneY,
                        onChange = { newX, newY -> AppSettings.setEqualizerTone(newX, newY) },
                        enabled = enabled,
                    )
                    ToneReadout(x = toneX, y = toneY)
                    Text(
                        text = stringResource(
                            if (focused) Res.string.equalizer_focused_footer
                            else Res.string.equalizer_broad_footer,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    ChoiceRow(
                        label = stringResource(Res.string.equalizer_broad),
                        selected = !focused,
                        onClick = { AppSettings.setEqualizerFocused(false) },
                        enabled = enabled,
                    )
                    RowDivider()
                    ChoiceRow(
                        label = stringResource(Res.string.equalizer_focused),
                        selected = focused,
                        onClick = { AppSettings.setEqualizerFocused(true) },
                        enabled = enabled,
                    )
                }

                EqualizerMode.MANUAL -> {
                    GroupDivider()
                    GroupHeader(stringResource(Res.string.equalizer_preset))
                    // The row that names where the bands came from, expanding
                    // in place — upstream's bottom sheet, folded into the
                    // dialog that hosts it. Selecting applies the curve and
                    // collapses; the row's own value keeps the last answer
                    // visible either way.
                    ClickableValueRow(
                        title = stringResource(Res.string.equalizer_preset),
                        value = preset.localizedLabel(),
                        onClick = { pickingPreset = !pickingPreset },
                        enabled = enabled,
                    )
                    if (pickingPreset) {
                        PresetList(
                            selected = preset,
                            onSelect = {
                                AppSettings.setEqualizerPreset(it)
                                pickingPreset = false
                            },
                        )
                    }
                    GroupDivider()
                    GroupHeader(stringResource(Res.string.equalizer_bands))
                    Text(
                        text = stringResource(Res.string.equalizer_bands_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BandSliders(
                        bands = bands,
                        onChange = { AppSettings.setEqualizerBands(it) },
                        enabled = enabled,
                    )
                    RowDivider()
                    // The destructive row upstream's sheet carried, kept: a
                    // curve someone wants out of needs one place to hand it
                    // back. Applying FLAT is the act, and the preset row
                    // renames itself through the derived flow.
                    Text(
                        text = stringResource(Res.string.equalizer_reset),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) {
                                haptics.play(Haptic.Select)
                                AppSettings.setEqualizerPreset(EqualizerPreset.FLAT)
                            }
                            .padding(vertical = 12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
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
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
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
private fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// ---- Tone pad --------------------------------------------------------------

/**
 * The dot grid and its puck, ported from upstream's `TonePad` — the gesture
 * math verbatim, proportional in both axes, so the dialog's different size
 * changes nothing about where a drag lands.
 *
 * Drawn rather than composed: eleven by eleven is 121 dots, and 121 layout
 * nodes re-measured on every frame of a drag is a stutter on the exact gesture
 * that has to feel direct. One [Canvas] redraws them for the cost of the
 * circles themselves.
 *
 * The puck snaps to whole steps as the finger moves, and springs between them
 * rather than teleporting — a control that jumps between dots reads as the
 * touch being dropped, and the spring is what says it was caught.
 */
@Composable
private fun TonePad(
    x: Int,
    y: Int,
    enabled: Boolean,
    onChange: (Int, Int) -> Unit,
) {
    val haptics = rememberHaptics()
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    val puckColor = MaterialTheme.colorScheme.background
    val shadowColor = Color.Black.copy(alpha = 0.16f)
    val steps = EqLayout.TONE_STEPS

    // The gesture block below is keyed on something that never changes, so it is
    // started once and captures whatever was in scope then. Read through these
    // and a second gesture begins from where the puck actually is, rather than
    // from where it was the first time this panel composed.
    val latestX by rememberUpdatedState(x)
    val latestY by rememberUpdatedState(y)

    // Spring, not tween: the puck has to keep up with a fast drag and still
    // settle rather than arrive and stop dead.
    val animatedX by animateFloatAsState(
        targetValue = x.toFloat(),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessHigh),
        label = "tonePadX",
    )
    val animatedY by animateFloatAsState(
        targetValue = y.toFloat(),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessHigh),
        label = "tonePadY",
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_INSET, vertical = 18.dp)
            .height(PAD_HEIGHT)
            .pointerInput(steps, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    // Claimed on the way down, before the scrolling parent gets
                    // to count this as the start of a fling. The dialog body
                    // scrolls; a pad that only took the gesture once it had
                    // moved would lose every vertical drag to it.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var lastX = latestX
                    var lastY = latestY
                    fun report(offset: Offset) {
                        val (newX, newY) = stepAt(offset, size.toSize(), PUCK_RADIUS.toPx(), steps)
                        if (newX != lastX || newY != lastY) {
                            lastX = newX
                            lastY = newY
                            haptics.play(Haptic.Tick)
                            onChange(newX, newY)
                        }
                    }
                    report(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (change.positionChanged()) {
                            change.consume()
                            report(change.position)
                        }
                    }
                }
            },
    ) {
        val inset = PUCK_RADIUS.toPx()
        val usableWidth = size.width - inset * 2
        val usableHeight = size.height - inset * 2
        val columns = steps * 2
        val dotRadius = DOT_RADIUS.toPx()

        for (column in 0..columns) {
            for (row in 0..columns) {
                // The row and column through dead centre are drawn up, so the
                // origin is findable without a reading — the same job the
                // detent does on a balance slider.
                val onAxis = column == steps || row == steps
                drawCircle(
                    color = dotColor.copy(alpha = if (onAxis) 0.55f else 0.3f),
                    radius = dotRadius,
                    center = Offset(
                        inset + usableWidth * column / columns,
                        inset + usableHeight * row / columns,
                    ),
                )
            }
        }

        val puckCentre = Offset(
            inset + usableWidth * (animatedX + steps) / columns,
            inset + usableHeight * (steps - animatedY) / columns,
        )
        // Three soft rings instead of a blur: a real shadow pass on a view this
        // small costs more than the circles it would be shading.
        for (ring in 3 downTo 1) {
            drawCircle(
                color = shadowColor.copy(alpha = shadowColor.alpha / ring),
                radius = inset + ring * 2f,
                center = puckCentre.copy(y = puckCentre.y + ring),
            )
        }
        drawCircle(color = puckColor, radius = inset, center = puckCentre)
    }
}

/** Which step the finger is over, clamped to the grid. */
private fun stepAt(offset: Offset, size: Size, inset: Float, steps: Int): Pair<Int, Int> {
    val usableWidth = (size.width - inset * 2).coerceAtLeast(1f)
    val usableHeight = (size.height - inset * 2).coerceAtLeast(1f)
    val fractionX = ((offset.x - inset) / usableWidth).coerceIn(0f, 1f)
    val fractionY = ((offset.y - inset) / usableHeight).coerceIn(0f, 1f)
    val x = (fractionX * steps * 2).roundToInt() - steps
    // Screen coordinates grow downwards and the control does not: up is more.
    val y = steps - (fractionY * steps * 2).roundToInt()
    return x to y
}

/** The two numbers under the pad: how far it is tilted, and how far contoured. */
@Composable
private fun ToneReadout(x: Int, y: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Waves,
            contentDescription = stringResource(Res.string.equalizer_tilt),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = signed(x),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(22.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ShowChart,
            contentDescription = stringResource(Res.string.equalizer_contour),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = signed(y),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Zero has no sign; anything else wears one, so the direction reads at a glance. */
private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

/** A row that is one of a set, ticked when it is the one in force. */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                if (!selected) {
                    haptics.play(Haptic.Select)
                    onClick()
                }
            }
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(Res.string.selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ---- Manual bands ----------------------------------------------------------

/**
 * Seven vertical faders, drawn — upstream's `BandSliders` verbatim.
 *
 * Material's `Slider` rotated a quarter turn keeps its horizontal touch
 * geometry and its horizontal thumb, which is why every Compose equaliser built
 * that way feels wrong under the pointer. These are a track, a fill and a knob
 * on a canvas, taking the gesture on first touch the way [TonePad] does.
 *
 * The fill runs from the centre rather than the bottom because the value it is
 * showing is signed: a band at −6 dB and a band at +6 dB are equal and opposite,
 * and a bar growing from the floor would draw them as a short one and a tall
 * one.
 */
@Composable
private fun BandSliders(
    bands: List<Float>,
    enabled: Boolean,
    onChange: (List<Float>) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_INSET - 4.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        EqLayout.MANUAL_BANDS_HZ.forEachIndexed { index, hz ->
            val value = bands.getOrElse(index) { 0f }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = formatGain(value),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (abs(value) < 0.05f) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                BandFader(
                    enabled = enabled,
                    value = value,
                    onChange = { updated ->
                        onChange(bands.toMutableList().also { it[index] = updated })
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formatFrequency(hz),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BandFader(
    value: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    val haptics = rememberHaptics()
    val trackColor = MaterialTheme.colorScheme.outline
    val fillColor = MaterialTheme.colorScheme.primary
    val knobColor = MaterialTheme.colorScheme.background
    val range = EqLayout.MANUAL_RANGE_DB
    // See [TonePad]: the gesture block outlives the composition that made it.
    val latest by rememberUpdatedState(value)

    Canvas(
        modifier = Modifier
            .width(FADER_WIDTH)
            .height(FADER_HEIGHT)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var last = latest
                    fun report(offset: Offset) {
                        val inset = KNOB_RADIUS.toPx()
                        val usable = (size.height - inset * 2).coerceAtLeast(1f)
                        val fraction = ((offset.y - inset) / usable).coerceIn(0f, 1f)
                        val raw = (1f - fraction * 2f) * range
                        val snapped = snapGain(raw, range)
                        if (abs(snapped - last) > 0.001f) {
                            // A tick per step, and the step at zero is the one
                            // people are aiming for when they undo a band.
                            haptics.play(Haptic.Tick)
                            last = snapped
                            onChange(snapped)
                        }
                    }
                    report(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (change.positionChanged()) {
                            change.consume()
                            report(change.position)
                        }
                    }
                }
            },
    ) {
        val inset = KNOB_RADIUS.toPx()
        val usable = size.height - inset * 2
        val centreY = inset + usable / 2
        val trackWidth = TRACK_WIDTH.toPx()
        val centreX = size.width / 2
        val knobY = inset + usable * (1f - (value / range + 1f) / 2f)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(centreX - trackWidth / 2, inset),
            size = Size(trackWidth, usable),
            cornerRadius = CornerRadius(trackWidth / 2),
        )
        if (abs(value) > 0.05f) {
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(centreX - trackWidth / 2, minOf(centreY, knobY)),
                size = Size(trackWidth, abs(centreY - knobY)),
                cornerRadius = CornerRadius(trackWidth / 2),
            )
        }
        // The zero line, drawn over the track so it survives a full-scale fill.
        drawLine(
            color = trackColor.copy(alpha = 0.9f),
            start = Offset(centreX - trackWidth, centreY),
            end = Offset(centreX + trackWidth, centreY),
            strokeWidth = 1.dp.toPx(),
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.18f),
            radius = inset + 1.5f,
            center = Offset(centreX, knobY + 1.5f),
        )
        drawCircle(color = knobColor, radius = inset, center = Offset(centreX, knobY))
    }
}

/**
 * Half-decibel steps, with a wider one at zero.
 *
 * Flat is the value a band gets dragged back to far more often than any other,
 * and it is the one value a finger cannot hit reliably on a 150 dp travel — so
 * it is given a detent to fall into rather than a coordinate to find.
 */
private fun snapGain(raw: Float, range: Float): Float {
    if (abs(raw) < ZERO_DETENT_DB) return 0f
    return ((raw * 2f).roundToInt() / 2f).coerceIn(-range, range)
}

private fun formatGain(value: Float): String = when {
    abs(value) < 0.05f -> "0"
    value > 0 -> "+" + trimGain(value)
    else -> trimGain(value)
}

/** "+6" rather than "+6.0", but "+6.5" when the half matters. */
private fun trimGain(value: Float): String =
    if (abs(value - value.roundToInt()) < 0.05f) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.getDefault(), "%.1f", value)
    }

/** "60", "2.5k", "14k" — the labels a hardware equaliser prints. */
private fun formatFrequency(hz: Float): String = when {
    hz < 1_000f -> hz.roundToInt().toString()
    hz % 1_000f == 0f -> "${(hz / 1_000f).roundToInt()}k"
    else -> String.format(Locale.getDefault(), "%.1fk", hz / 1_000f)
}

// ---- Presets ---------------------------------------------------------------

/**
 * The preset rows, expanded in place under the row that opened them —
 * upstream's `PresetSheet` without the bottom-sheet machinery, which a dialog
 * has no need for. Scrolls within its own cap; sixteen curves do not have to
 * fit the dialog's height, and the rows past the fold still have to be
 * reachable.
 */
@Composable
private fun PresetList(selected: EqualizerPreset, onSelect: (EqualizerPreset) -> Unit) {
    val haptics = rememberHaptics()
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        EqualizerPreset.entries.forEach { preset ->
            // Custom is a state the bands can be in, not a curve anyone can
            // choose, so it is only ever shown when it is the one in force.
            if (preset == EqualizerPreset.CUSTOM && selected != EqualizerPreset.CUSTOM) {
                return@forEach
            }
            val chosen = preset == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preset != EqualizerPreset.CUSTOM) {
                        haptics.play(Haptic.Select)
                        onSelect(preset)
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = preset.localizedLabel(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (chosen) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(Res.string.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * The settings dialog's `ClickableRow` grammar, with a trailing value — the
 * preset row names the curve the bands currently are, the way the output
 * device row names the endpoint.
 */
@Composable
private fun ClickableValueRow(
    title: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- Shared controls -------------------------------------------------------

/**
 * Sliding pill selector, upstream `SettingsSheet`'s own — two options here,
 * and the same animated pill so the control reads like the settings pages it
 * came from.
 */
@Composable
private fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val chosen = index == selectedIndex
            val pill by animateColorAsState(
                targetValue = if (chosen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                animationSpec = tween(160),
                label = "segmentPill",
            )
            val labelColor by animateColorAsState(
                targetValue = if (chosen) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(160),
                label = "segmentLabel",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(pill)
                    .clickable(enabled = enabled) {
                        if (!chosen) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        }
                    }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun EqualizerMode.localizedLabel(): String = stringResource(
    when (this) {
        EqualizerMode.DYNAMIC -> Res.string.equalizer_dynamic
        EqualizerMode.MANUAL -> Res.string.equalizer_manual
    },
)

@Composable
private fun EqualizerPreset.localizedLabel(): String = stringResource(
    when (this) {
        EqualizerPreset.FLAT -> Res.string.eq_preset_flat
        EqualizerPreset.ACOUSTIC -> Res.string.eq_preset_acoustic
        EqualizerPreset.BASS_BOOST -> Res.string.eq_preset_bass_boost
        EqualizerPreset.BASS_CUT -> Res.string.eq_preset_bass_cut
        EqualizerPreset.VOCAL -> Res.string.eq_preset_vocal
        EqualizerPreset.TREBLE_BOOST -> Res.string.eq_preset_treble_boost
        EqualizerPreset.TREBLE_CUT -> Res.string.eq_preset_treble_cut
        EqualizerPreset.LOUDNESS -> Res.string.eq_preset_loudness
        EqualizerPreset.SPOKEN_WORD -> Res.string.eq_preset_spoken_word
        EqualizerPreset.ELECTRONIC -> Res.string.eq_preset_electronic
        EqualizerPreset.ROCK -> Res.string.eq_preset_rock
        EqualizerPreset.HIP_HOP -> Res.string.eq_preset_hip_hop
        EqualizerPreset.JAZZ -> Res.string.eq_preset_jazz
        EqualizerPreset.CLASSICAL -> Res.string.eq_preset_classical
        EqualizerPreset.SMALL_SPEAKERS -> Res.string.eq_preset_small_speakers
        EqualizerPreset.LATE_NIGHT -> Res.string.eq_preset_late_night
        EqualizerPreset.CUSTOM -> Res.string.eq_preset_custom
    },
)

private val PAD_HEIGHT = 230.dp
private val PUCK_RADIUS = 19.dp
private val DOT_RADIUS = 2.5.dp
private val FADER_HEIGHT = 150.dp
private val FADER_WIDTH = 34.dp
private val TRACK_WIDTH = 5.dp
private val KNOB_RADIUS = 10.dp

/** Upstream `SettingsSheet`'s inset the pad and the fader row are built against. */
private val ROW_INSET = 16.dp

/** How close to flat a band has to be dragged before it snaps there. */
private const val ZERO_DETENT_DB = 0.6f
