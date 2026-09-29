package com.music.bitchord.data.settings

import kotlinx.coroutines.flow.MutableStateFlow

/** How a shelf or library page lays its items out. */
enum class LibraryViewType {
    LIST,
    GRID,
}

/**
 * The quality ceiling the resolver asks for, as the Android app's much larger
 * settings object defines it.
 */
enum class AudioQuality(
    val maxKbps: Int,
    val label: String,
) {
    LOW(64, "Low"),
    MEDIUM(Int.MAX_VALUE, "Medium"),
    HIGH(Int.MAX_VALUE, "High"),
    LOSSLESS(Int.MAX_VALUE, "Lossless"),
}

/**
 * Desktop stand-in for the Android settings object, which stores through
 * SharedPreferences and needs a Context.
 *
 * Only what the ported data layer actually reads lives here so far: the
 * quality ceiling [com.music.bitchord.data.innertube.StreamResolver] asks for.
 * There is no metered/mobile split on a desktop — the machine is the network —
 * so [effectiveAudioQuality] is just the one value, and it defaults to
 * Lossless the way both Android rungs do.
 */
object AppSettings {

    val audioQuality = MutableStateFlow(AudioQuality.LOSSLESS)

    val effectiveAudioQuality: AudioQuality
        get() = audioQuality.value

    /**
     * Turns the artwork-tint crossfades into cuts. Read by
     * [com.music.bitchord.ui.theme.rememberArtworkPalette]; the Android setting
     * that drives it lives in the settings sheet, which is a later slice.
     */
    val reduceAnimation = MutableStateFlow(false)

    /** Trades the live backdrop blur for a cheaper static one. Settings sheet, later slice. */
    val reduceDynamicBlur = MutableStateFlow(false)

    /** Swipe a queue row sideways to jump to it. Off by default upstream too. */
    val swipeToPlayNext = MutableStateFlow(false)

    /**
     * Whether the home feed's "Recents" shelf is a list or a grid. The Android
     * setter writes it to storage; here it lives for the session, which is all
     * the switch itself needs.
     */
    val homeRecentsViewType = MutableStateFlow(LibraryViewType.LIST)

    fun setHomeRecentsViewType(value: LibraryViewType) {
        homeRecentsViewType.value = value
    }
}
