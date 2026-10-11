// Ported from app/src/main/java/com/music/bitchord/data/stats/Backup.kt — the
// file shape, the typed values and the validation are the original's. What the
// desktop changes, all of it mechanical:
//   - A `java.io.File` destination rather than a content-resolver Uri: the
//     user picks a path with the platform's own chooser and this process owns it.
//   - `listening` is always empty: the desktop has no ListeningStats yet, so a
//     desktop backup carries the settings (and, through them, the search
//     history — the key lives in the same properties file). An Android backup's
//     listening months are read past, not dropped on the floor; a *desktop*
//     backup simply has nothing to put there.
//   - The settings themselves are the desktop's own keys, exported raw from the
//     properties store (every desktop value is a string) and imported through
//     the settings object's setters, so every collector — the equaliser's, the
//     dials' — hears an import the way it hears a drag.
package com.music.bitchord.data.backup

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.settings.EqualizerMode
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.data.settings.ThemeSetting
import com.music.bitchord.data.settings.decodeEqualizerBands
import com.music.bitchord.data.settings.lyricsSourceOrderFrom
import com.music.bitchord.data.settings.lyricsSourcesFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The whole of what this app knows about you, as one JSON file you own.
 *
 * Two errands, and they are the same errand: moving to a new machine, and being
 * able to see what is being kept. Everything that is not a re-fetchable cache —
 * the settings, the search history that rides in the same file — lives only on
 * this device, and a deleted user profile takes it with no way back, so there
 * has to be a way out.
 */
object Backup {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** A suggested filename, dated so successive exports don't collide. */
    fun suggestedName(): String =
        "bitchord-backup-${DateTimeFormatter.ofPattern("yyyy-MM-dd").format(
            Instant.now().atZone(ZoneId.systemDefault()),
        )}.json"

    /**
     * Writes a backup to [target], a file the user picked.
     *
     * The settings go out raw — every desktop value is a string in the
     * properties store, typed up here as [PrefValue.STRING] so the JSON shape
     * is the one an Android reader (and writer) already speaks.
     */
    suspend fun exportTo(target: java.io.File): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val file = BackupFile(
                versionName = BuildConfig.VERSION_NAME,
                exportedAt = Instant.now().toString(),
                settings = AppSettings.exportPrefs().mapValues { (_, value) ->
                    PrefValue(type = PrefValue.STRING, value = value)
                },
                listening = emptyList(),
            )
            val text = json.encodeToString(BackupFile.serializer(), file)
            target.writeText(text, Charsets.UTF_8)
            file.settings.size
        }
    }

    /**
     * Reads [source] and replaces this device's settings with it.
     *
     * Validated before anything is written: a file that isn't one of ours, or
     * is from a schema this build can't read, is refused whole. A half-applied
     * import is worse than a refused one — it leaves settings from two devices
     * mixed together with nothing to say which came from where.
     */
    suspend fun importFrom(source: java.io.File): Result<Summary> = withContext(Dispatchers.IO) {
        runCatching {
            val text = source.readText(Charsets.UTF_8)
            val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
                .getOrElse { error("That doesn't look like a BitChord backup") }
            require(file.app == APP_TAG) { "That backup is from another app" }
            require(file.version <= SCHEMA_VERSION) {
                "That backup was written by a newer version of BitChord"
            }

            applySettings(file.settings)
            // Shares AppSettings' preference file, so the search-history key —
            // written by the line above when the backup carried one — has
            // already been replaced; the flow just doesn't know yet.
            SearchHistory.reload()
            Summary(
                settings = file.settings.size,
                from = file.versionName,
                at = file.exportedAt,
            )
        }
    }

    /**
     * The import's working half: each backup key the desktop knows lands
     * through the setter that owns it, so the flows — and every collector
     * hanging off them — see the imported value exactly as they would a drag
     * in the settings dialog. Keys this build has no opinion about (Android's
     * metered pairs, scrobbling tokens, listening buckets) are skipped rather
     * than guessed at; the device-local keys never got this far.
     */
    private fun applySettings(settings: Map<String, PrefValue>) {
        fun raw(pref: PrefValue): String? = when (pref.type) {
            PrefValue.STRING_SET -> pref.values.joinToString(",")
            else -> pref.value
        }
        for ((key, pref) in settings) {
            val value = raw(pref) ?: continue
            when (key) {
                "shuffle_enabled" -> AppSettings.setShuffleEnabled(value == "true")
                "repeat_mode" -> value.toIntOrNull()?.let(AppSettings::setRepeatMode)
                "autoplay" -> AppSettings.setAutoplay(value == "true")
                "crossfade_seconds" -> value.toIntOrNull()?.let(AppSettings::setCrossfadeSeconds)
                "playback_speed" -> value.toFloatOrNull()?.let(AppSettings::setPlaybackSpeed)
                "loudness_normalization" -> AppSettings.setLoudnessNormalization(value != "false")
                "skip_silence" -> AppSettings.setSkipSilence(value == "true")
                "hide_volume_bar" -> AppSettings.setHideVolumeBar(value == "true")
                "reduce_animation" -> AppSettings.setReduceAnimation(value == "true")
                "swipe_to_play_next" -> AppSettings.setSwipeToPlayNext(value == "true")
                "theme_setting" -> ThemeSetting.entries.firstOrNull { it.name == value }
                    ?.let(AppSettings::setThemeSetting)
                "translucent_window" -> AppSettings.setTranslucentWindow(value != "false")
                "liquid_glass" -> AppSettings.setLiquidGlass(value == "true")
                "reduce_dynamic_blur" -> AppSettings.setReduceDynamicBlur(value == "true")
                "language" -> AppSettings.setLanguage(value.takeIf { it.isNotBlank() })
                "animated_canvas" -> AppSettings.setAnimatedCanvas(value != "false")
                "prioritize_spotify_canvas" -> AppSettings.setPrioritizeSpotifyCanvas(value == "true")
                "show_nerd_stats" -> AppSettings.setShowNerdStats(value == "true")
                "equalizer_enabled" -> AppSettings.setEqualizerEnabled(value == "true")
                "equalizer_mode" -> EqualizerMode.entries.firstOrNull { it.name == value }
                    ?.let(AppSettings::setEqualizerMode)
                "equalizer_tone_x" -> value.toIntOrNull()?.let { AppSettings.setEqualizerTone(it, AppSettings.equalizerToneY.value) }
                "equalizer_tone_y" -> value.toIntOrNull()?.let { AppSettings.setEqualizerTone(AppSettings.equalizerToneX.value, it) }
                "equalizer_focused" -> AppSettings.setEqualizerFocused(value == "true")
                "equalizer_bands" -> AppSettings.setEqualizerBands(decodeEqualizerBands(value))
                "audio_quality" -> AudioQuality.entries.firstOrNull { it.name == value }
                    ?.let { AppSettings.setAudioQuality(it) }
                "download_quality" -> DownloadQuality.entries.firstOrNull { it.name == value }
                    ?.let(AppSettings::setDownloadQuality)
                "dolby_atmos" -> AppSettings.setDolbyAtmos(value != "false")
                "upgrade_length_slack_seconds" -> value.toIntOrNull()?.let(AppSettings::setUpgradeLengthSlackSeconds)
                "synced_lyrics" -> AppSettings.setSyncedLyrics(value != "false")
                "lyrics_offset_ms" -> value.toIntOrNull()?.let(AppSettings::setLyricsOffsetMs)
                "translation_language" -> AppSettings.setTranslationLanguage(value)
                "prioritize_syllable_sync" -> AppSettings.setPrioritizeSyllableSync(value == "true")
                // The two source lists ride their own encodings; the seen-list
                // is written beside [setLyricsSources] and never imported alone.
                "lyrics_sources" -> AppSettings.setLyricsSources(lyricsSourcesFrom(value, null))
                "lyrics_source_order" -> AppSettings.setLyricsSourceOrder(lyricsSourceOrderFrom(value))
                "webdav_url", "webdav_username" -> AppSettings.acceptImported(
                    key,
                    value,
                )
                "local_music_sort" -> LocalMusicSort.entries.firstOrNull { it.name == value }
                    ?.let(AppSettings::setLocalMusicSort)
                "local_music_view_type" -> LibraryViewType.entries.firstOrNull { it.name == value }
                    ?.let(AppSettings::setLocalMusicViewType)
                // `local_music_folders` is device-local and never arrives here;
                // anything else this build does not know is skipped on purpose.
            }
        }
    }

    /** What an import turned out to contain, for the line shown afterwards. */
    data class Summary(
        val settings: Int,
        val from: String,
        val at: String,
    )

    private const val APP_TAG = "bitchord"

    /**
     * Bump when the shape below stops being readable by an older build. A file
     * from a *newer* schema is refused rather than partially read; one from an
     * older schema is read as-is, since every field has a default.
     */
    private const val SCHEMA_VERSION = 1

    @Serializable
    data class BackupFile(
        val app: String = APP_TAG,
        val version: Int = SCHEMA_VERSION,
        /** Which build wrote it — for the reader, not for any logic here. */
        val versionName: String = "",
        val exportedAt: String = "",
        val settings: Map<String, PrefValue> = emptyMap(),
        val listening: List<StoredBucket> = emptyList(),
    )

    /**
     * The listening buckets an Android backup may carry. The desktop has no
     * stats to fill them with and no page to show them on — the field exists so
     * the JSON an Android build wrote still parses here, and the data survives
     * a desktop round trip untouched rather than being silently eaten.
     */
    @Serializable
    data class StoredBucket(val month: String = "", val payload: String = "")

    /** One preference, with the type it has to be restored as. */
    @Serializable
    data class PrefValue(
        val type: String,
        val value: String? = null,
        val values: List<String> = emptyList(),
    ) {
        companion object {
            const val STRING = "string"
            const val STRING_SET = "stringSet"
            // The other upstream type names exist so an Android file's types
            // are recognisable; the desktop's own values all go out as STRING.
            const val BOOLEAN = "bool"
            const val INT = "int"
            const val LONG = "long"
            const val FLOAT = "float"
        }
    }
}
