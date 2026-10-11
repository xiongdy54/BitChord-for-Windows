package com.music.bitchord.data.settings

import com.music.bitchord.data.model.SearchHistoryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What's been searched for lately, kept on this device only.
 *
 * YouTube Music has an account-level search history, but reading it back needs
 * a separate signed-in call on every keystroke's worth of empty query — and it
 * would be wrong for a signed-out user. A local list is instant, works either
 * way, and is what Spotify and Apple Music show in the same spot.
 *
 * Ported from upstream's `SearchHistory.kt` with one desktop-specific decision:
 * the list lives in the *same* file and key as upstream's
 * (`settings.properties`, `search_history`), through the FileStore instance
 * [AppSettings] already owns — a second FileStore over the same file would
 * clobber whatever the settings object writes (the trap [Main][com.music.bitchord.desktop.Main]'s
 * window-store note describes), and sharing the instance is also what makes the
 * key ride along in a backup exactly the way it rides in an Android one.
 */
object SearchHistory {

    /** Deep enough to be useful, shallow enough that the list stays scannable. */
    const val MAX_ENTRIES = 20

    private val json = Json
    private val serializer = ListSerializer(SearchHistoryEntity.serializer())

    private val _recent = MutableStateFlow(readStored())

    /** Most recent first. */
    val recent: StateFlow<List<SearchHistoryEntity>> = _recent.asStateFlow()

    /**
     * Re-reads the list off disk.
     *
     * A backup import overwrites the shared file without ever calling
     * [record] — the same reason upstream reloads — so the flow would
     * otherwise go on serving the list the old device had.
     */
    fun reload() {
        _recent.value = readStored()
    }

    /**
     * Records [entity], or moves it back to the top if it's already there.
     * Matching is by id so re-tapping an existing entity updates its timestamp.
     */
    fun record(entity: SearchHistoryEntity) = save(merged(entity, _recent.value))

    fun remove(id: String) {
        save(_recent.value.filterNot { it.id.equals(id, ignoreCase = true) })
    }

    fun clear() = save(emptyList())

    private fun save(value: List<SearchHistoryEntity>) {
        _recent.value = value
        AppSettings.store.putString(KEY_HISTORY, json.encodeToString(serializer, value))
    }

    private fun readStored(): List<SearchHistoryEntity> = runCatching {
        json.decodeFromString(serializer, AppSettings.store.getString(KEY_HISTORY, "[]"))
    }.getOrDefault(emptyList())

    private const val KEY_HISTORY = "search_history"
}

/**
 * [SearchHistory.record]'s arithmetic, pure: [entity] moves to the front,
 * its previous self (matched case-insensitively by id, upstream's rule) leaves,
 * and the list is cut back to the cap. Split out so a test can pin the shape
 * without writing the settings file a real record would.
 */
internal fun merged(entity: SearchHistoryEntity, current: List<SearchHistoryEntity>): List<SearchHistoryEntity> =
    (listOf(entity) + current.filterNot { it.id.equals(entity.id, ignoreCase = true) })
        .take(SearchHistory.MAX_ENTRIES)
