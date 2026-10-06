package com.music.bitchord.data.settings

import com.music.bitchord.data.AppFiles
import com.music.bitchord.data.FileStore
import com.music.bitchord.playback.RepeatMode
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
 *
 * Two of the settings below do store, though: the two the player has to be
 * asked about again before the user touches anything.
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
     * Put this track's quality ceiling and the resolver's recorded figures on the
     * foot of the sleeve — [com.music.bitchord.ui.player.SleeveNerdStats]'s gate.
     *
     * The Android app gates that composable on this switch and starts it off
     * (`AppSettings.kt:407`), so the field exists here to give the ported gate
     * something honest to read rather than a trigger of its own: with the switch
     * off, nothing is drawn, which is upstream's behaviour. The settings sheet
     * that flips it is a later slice, so like the three switches above this lives
     * for the session only — no persistence key.
     */
    val showNerdStats = MutableStateFlow(false)

    fun setShowNerdStats(value: Boolean) {
        showNerdStats.value = value
    }

    /**
     * Whether the home feed's "Recents" shelf is a list or a grid. The Android
     * setter writes it to storage; here it lives for the session, which is all
     * the switch itself needs.
     */
    val homeRecentsViewType = MutableStateFlow(LibraryViewType.LIST)

    fun setHomeRecentsViewType(value: LibraryViewType) {
        homeRecentsViewType.value = value
    }

    /** The original keeps these in SharedPreferences (`bitchord_settings`); here it is one file. */
    private val prefs: FileStore by lazy { FileStore(AppFiles.file("settings.properties")) }

    /**
     * Whether the queue is held in shuffled order, and how the player repeats.
     *
     * The two queue flags persist because the player reads them back on the next
     * launch: `QueueShuffle` decides whether a fresh queue goes in shuffled, and
     * repeat mode is restored the same way. Booleans and small ints ride
     * [FileStore]'s string accessors rather than widening it — the same one file,
     * and no new surface on the store the resolver's config already depends on.
     */
    val shuffleEnabled = MutableStateFlow(prefs.getString(KEY_SHUFFLE_ENABLED, "false") == "true")
    val repeatMode = MutableStateFlow(prefs.getString(KEY_REPEAT_MODE, "0").toIntOrNull() ?: RepeatMode.OFF)

    /**
     * Which colour scheme the window paints, and the only way the desktop
     * build's user gets a dark UI on a light Windows. Upstream has no
     * equivalent switch — it follows the system, and the desktop build did too
     * until this existed — so the names are the desktop's own. Persisted
     * because a user who chose dark means it next launch too.
     */
    val themeSetting = MutableStateFlow(
        prefs.getString(KEY_THEME_SETTING, ThemeSetting.SYSTEM.name)
            .let { stored -> ThemeSetting.entries.firstOrNull { it.name == stored } }
            ?: ThemeSetting.SYSTEM,
    )

    fun setThemeSetting(value: ThemeSetting) {
        themeSetting.value = value
        prefs.putString(KEY_THEME_SETTING, value.name)
    }

    /**
     * The interface language as a BCP-47 tag (`"en"`, `"zh"`), or null to
     * follow whatever the machine's JVM default is. Strings resolve from the
     * JVM locale, which is decided once before composition — so a change here
     * takes effect on the next launch, which is what the settings dialog says.
     */
    val language: MutableStateFlow<String?> = MutableStateFlow(
        prefs.getString(KEY_LANGUAGE, "").takeIf { it.isNotBlank() },
    )

    fun setLanguage(value: String?) {
        language.value = value
        prefs.putString(KEY_LANGUAGE, value.orEmpty())
    }

    fun setShuffleEnabled(value: Boolean) {
        shuffleEnabled.value = value
        prefs.putString(KEY_SHUFFLE_ENABLED, value.toString())
    }

    fun setRepeatMode(value: Int) {
        repeatMode.value = value
        prefs.putString(KEY_REPEAT_MODE, value.toString())
    }

    private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_THEME_SETTING = "theme_setting"
    private const val KEY_LANGUAGE = "language"
}

/** Which colour scheme the window paints. See [AppSettings.themeSetting]. */
enum class ThemeSetting {
    /** Whatever the machine itself is set to — the behaviour before any switch existed. */
    SYSTEM,

    /** Light, whatever the machine says. */
    LIGHT,

    /** Dark, whatever the machine says. */
    DARK,
}
