package com.music.bitchord.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// Ported from app/src/main/java/com/music/bitchord/ui/haptics/Haptics.kt. The
// vocabulary is kept so the ported call sites do not change; the machinery
// behind it — VibrationEffect, the Vibrator and the per-device capability probe
// — has no counterpart on a desktop machine, so every call is a no-op.

/**
 * The beats the app asks for, and what each one means. A desktop plays none of
 * them, but the names are what the call sites are written in.
 */
enum class Haptic {
    /**
     * The lightest single beat, for something that repeats while a finger is
     * still down — a drag crossing a tab boundary, say.
     */
    Tick,

    /** A plain button press with no state behind it: More, Download, Menu. */
    Tap,

    /** A discrete choice landing: a tab, a filter pill, the end of a scrub. */
    Select,

    /** Switching something on — a light lead-in *rising* into a firm beat. */
    ToggleOn,

    /** Switching it back off — the same pair mirrored, so it falls away. */
    ToggleOff,

    /** Forward through the queue: an accelerating triplet. */
    SkipNext,

    /** Backward: [SkipNext] reversed, which is what makes the pair legible. */
    SkipPrevious,

    /** Playback starting — swells into the beat that lands. */
    Resume,

    /** Playback stopping — lands first, then releases. */
    Pause,

    /** Something growing to fill the screen, e.g. the mini player opening. */
    Expand,
}

/** A handle on the device's motor, obtained with [rememberHaptics]. */
class Haptics {

    fun play(haptic: Haptic) {
        // No motor to drive.
    }
}

@Composable
fun rememberHaptics(): Haptics = remember { Haptics() }
