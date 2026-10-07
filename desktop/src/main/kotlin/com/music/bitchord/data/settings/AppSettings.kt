package com.music.bitchord.data.settings

import com.music.bitchord.data.AppFiles
import com.music.bitchord.data.FileStore
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.PaxSenix
import com.music.bitchord.data.lyrics.normalizePaxSenixApiKey
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

    // ── Lyrics ───────────────────────────────────────────────────────────────
    //
    // The lyric settings ride the same property file under the same string
    // encoding as the keys above, with the Android object's upgrade semantics
    // kept: a source added by an app update is enabled rather than silently
    // off, and one dropped from a stored order falls back into declared order.

    /**
     * Time-synced lyrics on the player, lit up as they are sung.
     *
     * On by default — it is most of the point of the player screen — but it
     * reaches third-party lyric databases for every track played, so it stays
     * a switch, and [lyricsSources] narrows which of them get asked.
     */
    val syncedLyrics = MutableStateFlow(prefs.getString(KEY_SYNCED_LYRICS, "true") == "true")

    /** Positive values delay synced lyrics; negative values bring them forward. */
    val lyricsOffsetMs = MutableStateFlow(prefs.getString(KEY_LYRICS_OFFSET_MS, "0").toIntOrNull() ?: 0)

    /**
     * Blurs unfocused lyric lines, keeping the active line sharp. Upstream
     * persists this; the desktop takes the same default and defers the storage
     * key until the settings row that flips it exists — a key nothing writes
     * yet is not a saving.
     */
    val lyricsBlur = MutableStateFlow(true)

    fun setLyricsBlur(value: Boolean) {
        lyricsBlur.value = value
    }

    /**
     * Which language the lyrics translate button translates *into*.
     *
     * Blank — the default — means "whatever the app is set to", and is stored
     * as blank rather than resolved once: someone who has never touched this
     * has expressed no preference, and switching the app language should carry
     * their lyrics with it rather than leaving them on the language they
     * happened to be reading the day the setting was written.
     */
    val translationLanguage = MutableStateFlow(prefs.getString(KEY_TRANSLATION_LANGUAGE, ""))

    /** The databases [syncedLyrics] may ask. Empty is the same as off. */
    val lyricsSources = MutableStateFlow(readLyricsSources())

    /**
     * The order [lyricsSources] are asked in — every enabled source is asked
     * at once, but a higher-priority one still pending is never preempted by a
     * lower one that happened to answer first. Reordered from Settings, so
     * this is a full permutation of [LyricsSource.entries] rather than a
     * subset — enabling and ordering are independent choices.
     */
    val lyricsSourceOrder = MutableStateFlow(readLyricsSourceOrder())

    /**
     * Off, the highest-priority source to answer at all is taken as the
     * lyrics, word-synced or not. On, a merely line-synced answer is held as a
     * fallback while the rest of [lyricsSourceOrder] is still checked for a
     * word-synced one.
     */
    val prioritizeSyllableSync = MutableStateFlow(prefs.getString(KEY_PRIORITIZE_SYLLABLE_SYNC, "false") == "true")

    /** User-issued credential required by api.paxsenix.org. */
    val paxSenixApiKey = MutableStateFlow(prefs.getString(KEY_PAXSENIX_API_KEY, ""))

    init {
        PaxSenix.setApiKey(paxSenixApiKey.value)
    }

    fun setSyncedLyrics(value: Boolean) {
        syncedLyrics.value = value
        prefs.putString(KEY_SYNCED_LYRICS, value.toString())
    }

    fun setLyricsOffsetMs(value: Int) {
        lyricsOffsetMs.value = value
        prefs.putString(KEY_LYRICS_OFFSET_MS, value.toString())
    }

    fun setTranslationLanguage(value: String) {
        translationLanguage.value = value
        prefs.putString(KEY_TRANSLATION_LANGUAGE, value)
    }

    fun setLyricsSources(value: Set<LyricsSource>) {
        lyricsSources.value = value
        prefs.putString(KEY_LYRICS_SOURCES, value.joinToString(",") { it.name })
        // Everything that was on the list this choice was made from, so a
        // later build can tell a source the user turned off from one they
        // have never been shown. See [readLyricsSources].
        prefs.putString(KEY_LYRICS_SOURCES_SEEN, LyricsSource.entries.joinToString(",") { it.name })
    }

    fun setLyricsSourceOrder(value: List<LyricsSource>) {
        lyricsSourceOrder.value = value
        prefs.putString(KEY_LYRICS_SOURCE_ORDER, value.joinToString(",") { it.name })
    }

    fun setPrioritizeSyllableSync(value: Boolean) {
        prioritizeSyllableSync.value = value
        prefs.putString(KEY_PRIORITIZE_SYLLABLE_SYNC, value.toString())
    }

    fun setPaxSenixApiKey(value: String) {
        val normalized = normalizePaxSenixApiKey(value)
        paxSenixApiKey.value = normalized
        prefs.putString(KEY_PAXSENIX_API_KEY, normalized)
        PaxSenix.setApiKey(normalized)
    }

    /**
     * Puts the source list, its order and [prioritizeSyllableSync] back the
     * way a fresh install finds them. [syncedLyrics] itself is left alone —
     * this is "start over on *which* lyrics", not "turn lyrics off".
     */
    fun resetLyricsSourceSettings() {
        setLyricsSources(LyricsSource.entries.toSet())
        setLyricsSourceOrder(LyricsSource.entries)
        setPrioritizeSyllableSync(false)
    }

    /**
     * A source *added* by an upgrade is enabled rather than left out. Absence
     * from a saved list is a decision only about the sources that list was
     * chosen from; a new one was never on it, so its absence says nothing.
     * [KEY_LYRICS_SOURCES_SEEN] is what makes the two cases distinguishable —
     * before it existed, [LEGACY_SOURCES] stands in as the list of everything
     * there was to have an opinion about.
     */
    private fun readLyricsSources(): Set<LyricsSource> =
        lyricsSourcesFrom(
            stored = prefs.getStringOrNull(KEY_LYRICS_SOURCES),
            seen = prefs.getStringOrNull(KEY_LYRICS_SOURCES_SEEN),
        )

    private fun List<String>.toSources(): Set<LyricsSource> =
        mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name } }.toSet()

    /**
     * A named source dropped from the stored order — an app update reordered
     * since it was saved — falls out on read; one added since is appended, in
     * [LyricsSource]'s own declared order, so a fresh install and an upgraded
     * one agree on where a new source lands until the user says otherwise.
     */
    private fun readLyricsSourceOrder(): List<LyricsSource> =
        lyricsSourceOrderFrom(prefs.getStringOrNull(KEY_LYRICS_SOURCE_ORDER))

    private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_THEME_SETTING = "theme_setting"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_SYNCED_LYRICS = "synced_lyrics"
    private const val KEY_LYRICS_OFFSET_MS = "lyrics_offset_ms"
    private const val KEY_TRANSLATION_LANGUAGE = "translation_language"
    private const val KEY_LYRICS_SOURCES = "lyrics_sources"
    private const val KEY_LYRICS_SOURCES_SEEN = "lyrics_sources_seen"
    private const val KEY_LYRICS_SOURCE_ORDER = "lyrics_source_order"
    private const val KEY_PRIORITIZE_SYLLABLE_SYNC = "prioritize_syllable_sync"
    private const val KEY_PAXSENIX_API_KEY = "paxsenix_api_key"
}

/**
 * The source list a stored string (and the upgrade bookkeeping beside it)
 * resolves to. `stored == null` is a fresh install — every source on; a
 * stored-but-empty string is a user who turned them all off, which is
 * different and is kept different. A source the `seen` list doesn't name was
 * added by an app update and is enabled rather than silently off.
 */
internal fun lyricsSourcesFrom(
    stored: String?,
    seen: String?,
    legacy: Set<LyricsSource> = LEGACY_LYRICS_SOURCES,
): Set<LyricsSource> {
    if (stored == null) return LyricsSource.entries.toSet()
    val chosen = stored.split(",").toLyricsSources()
    val seenSources = seen?.split(",")?.toLyricsSources() ?: legacy
    return chosen + LyricsSource.entries.filter { it !in seenSources }
}

/**
 * The ask order a stored string resolves to: unknown names fall out, sources
 * missing from the string append in declared order — so a fresh install and
 * an upgraded one agree on where a new source lands until the user says
 * otherwise.
 */
internal fun lyricsSourceOrderFrom(stored: String?): List<LyricsSource> {
    if (stored == null) return LyricsSource.entries
    val saved = stored.split(",").mapNotNull { name ->
        LyricsSource.entries.firstOrNull { it.name == name }
    }
    return saved + LyricsSource.entries.filter { it !in saved }
}

private fun List<String>.toLyricsSources(): Set<LyricsSource> =
    mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name } }.toSet()

/**
 * The sources that existed before the seen-key was written. Fixed forever: it
 * describes what an old build could have saved, so it does not grow when
 * [LyricsSource] does.
 */
private val LEGACY_LYRICS_SOURCES = setOf(
    LyricsSource.LYRICS_PLUS,
    LyricsSource.PAXSENIX,
    LyricsSource.BETTER_LYRICS,
    LyricsSource.SIMP_MUSIC,
    LyricsSource.KUGOU,
    LyricsSource.LRCLIB,
    LyricsSource.MUSIXMATCH,
    LyricsSource.GENIUS,
)

/** Which colour scheme the window paints. See [AppSettings.themeSetting]. */
enum class ThemeSetting {
    /** Whatever the machine itself is set to — the behaviour before any switch existed. */
    SYSTEM,

    /** Light, whatever the machine says. */
    LIGHT,

    /** Dark, whatever the machine says. */
    DARK,
}
