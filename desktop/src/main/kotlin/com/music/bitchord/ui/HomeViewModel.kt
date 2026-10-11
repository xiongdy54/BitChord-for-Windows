package com.music.bitchord.ui

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The home feed's state, lifted out of the Android MainViewModel's home section
 * (`app/.../ui/MainViewModel.kt`), keeping its shape: the core feed and each
 * supplement shelf are separate requests that land as they arrive, shelves are
 * deduplicated by title so a slow supplement can't double-park itself, and a
 * refresh replaces the page without blanking it first.
 *
 * Two things are deliberately simpler than upstream. There is one listener and
 * no account switching, so the guard against a superseded load is a plain
 * counter rather than the signed-in identity. And the "recently played" shelf
 * is the listener's own account fact: it is asked for only when
 * [signedIn] — wired from the session controller — says someone is in, and it
 * publishes ahead of the core feed so YouTube's own stale shelf of the same
 * name loses the title race (the dedupe's ruling, upstream's replacement
 * semantics).
 */
class HomeViewModel(private val scope: CoroutineScope) {

    /**
     * Whether a listener is signed in — set once by [Main][com.music.bitchord.desktop.Main]
     * from the session controller's flow. Default false keeps a bare
     * construction (tests, previews) asking for nothing signed-in.
     */
    var signedIn: () -> Boolean = { false }

    private val _home = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val home: StateFlow<UiState<List<HomeShelf>>> = _home.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    /**
     * True while the signed-in shelf's request is in flight — the skeleton
     * that owns the page's lead slot while it waits.
     */
    private val _recentlyPlayedLoading = MutableStateFlow(false)
    val recentlyPlayedLoading: StateFlow<Boolean> = _recentlyPlayedLoading.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var continuation: String? = null
    private val seenTitles = mutableSetOf<String>()

    /** Bumped by every load; a response whose generation is stale is dropped. */
    private var generation = 0L

    fun loadHome() {
        val current = ++generation
        _home.value = UiState.Loading
        continuation = null
        seenTitles.clear()
        _loadingMore.value = false
        fetchRecentlyPlayed(current)
        scope.launch {
            YtMusicRepository.home()
                .onSuccess { feed ->
                    if (current != generation) return@onSuccess
                    continuation = feed.continuation
                    publish(feed.shelves, replace = _home.value !is UiState.Success)
                }
                .onFailure { failure ->
                    if (current == generation && _home.value !is UiState.Success) {
                        _home.value = UiState.Error(failure.message ?: failure.javaClass.simpleName)
                    }
                }
        }
        fetchSupplements(current)
    }

    /** Keeps the current page up until the replacement arrives. */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        val current = ++generation
        continuation = null
        seenTitles.clear()
        fetchRecentlyPlayed(current)
        scope.launch {
            YtMusicRepository.home()
                .onSuccess { feed ->
                    if (current == generation) {
                        continuation = feed.continuation
                        publish(feed.shelves, replace = true)
                    }
                }
            fetchSupplements(current)
            // The supplement fan-out is fire-and-forget, so the spinner ends with
            // the core feed rather than with the slowest shelf.
            if (current == generation) _refreshing.value = false
        }
    }

    fun loadMore() {
        val token = continuation ?: return
        if (_loadingMore.value) return
        _loadingMore.value = true
        val current = generation
        scope.launch {
            YtMusicRepository.moreHome(token)
                .onSuccess { feed ->
                    if (current == generation) {
                        continuation = feed.continuation
                        publish(feed.shelves, replace = false)
                    }
                }
            _loadingMore.value = false
        }
    }

    private fun fetchSupplements(current: Long) {
        YtMusicRepository.HOME_SUPPLEMENT_BROWSE_IDS.forEach { browseId ->
            scope.launch {
                YtMusicRepository.homeSupplement(browseId).onSuccess { shelves ->
                    if (current == generation) publish(shelves, replace = false)
                }
            }
        }
    }

    /**
     * The signed-in lead shelf, fetched ahead of the core feed so it can take
     * the page's first slot: [publish] with `prepend = true` puts it at the
     * front, and the title dedupe then drops YouTube's own "Recents" shelf
     * when the core feed arrives with one — the same replacement upstream's
     * `publishHomeShelves(prepend = true)` performs, falling out of the dedupe
     * rather than a special case. Signed out (or an empty history — the
     * repository answers null) this is one `signedIn` read and no request,
     * which is the page the app showed before sign-in existed.
     */
    private fun fetchRecentlyPlayed(current: Long) {
        if (!signedIn()) return
        _recentlyPlayedLoading.value = true
        scope.launch {
            YtMusicRepository.homeRecentlyPlayed()
                .onSuccess { shelf ->
                    if (current == generation) {
                        shelf?.let { publish(listOf(it), replace = false, prepend = true) }
                    }
                }
            if (current == generation) _recentlyPlayedLoading.value = false
        }
    }

    /**
     * @param replace true when these shelves *are* the page — a first load or a
     *   refresh — rather than more arriving beside what is already there.
     * @param prepend true when these shelves lead the page rather than trail
     *   it — the recently-played shelf's own seat.
     */
    private fun publish(shelves: List<HomeShelf>, replace: Boolean, prepend: Boolean = false) {
        val merged = mergeShelves(
            existing = (_home.value as? UiState.Success)?.data.orEmpty(),
            incoming = shelves,
            seenTitles = seenTitles,
            replace = replace,
            prepend = prepend,
        ) ?: return
        _home.value = UiState.Success(merged)
    }
}

/**
 * [HomeViewModel.publish]'s arithmetic, pure: incoming shelves lose any whose
 * title the [seenTitles] set has claimed (the dedupe that keeps a slow
 * supplement from double-parking, and — with the recently-played shelf
 * publishing first — YouTube's own same-titled shelf from riding in second),
 * and the survivors land ahead of the page on [prepend] or behind it otherwise.
 * Null means "nothing changed", which a trailing append with no newcomers is.
 */
internal fun mergeShelves(
    existing: List<HomeShelf>,
    incoming: List<HomeShelf>,
    seenTitles: MutableSet<String>,
    replace: Boolean,
    prepend: Boolean,
): List<HomeShelf>? {
    val keyed = incoming.filter { seenTitles.add(it.title.lowercase(Locale.ROOT)) }
    return when {
        replace || existing.isEmpty() -> if (prepend) keyed + existing else keyed
        keyed.isNotEmpty() -> if (prepend) keyed + existing else existing + keyed
        else -> null
    }
}
