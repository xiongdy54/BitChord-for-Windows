package com.music.bitchord.data.settings

import kotlinx.coroutines.flow.MutableStateFlow

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
}
