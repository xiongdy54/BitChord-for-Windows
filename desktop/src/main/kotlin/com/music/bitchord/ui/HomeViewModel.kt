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
 * counter rather than the signed-in identity. And nothing is signed in yet, so
 * the "recently played" shelf — a signed-in endpoint — is never asked for; the
 * page shows the sign-in banner in its place, exactly as the app does when
 * signed out.
 */
class HomeViewModel(private val scope: CoroutineScope) {

    private val _home = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val home: StateFlow<UiState<List<HomeShelf>>> = _home.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    /** Never true on desktop yet — the shelf it stands for is signed-in only. */
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
     * @param replace true when these shelves *are* the page — a first load or a
     *   refresh — rather than more arriving beside what is already there.
     */
    private fun publish(shelves: List<HomeShelf>, replace: Boolean) {
        val keyed = shelves.filter { seenTitles.add(it.title.lowercase(Locale.ROOT)) }
        val existing = (_home.value as? UiState.Success)?.data.orEmpty()
        when {
            replace || _home.value !is UiState.Success -> _home.value = UiState.Success(keyed)
            keyed.isNotEmpty() -> _home.value = UiState.Success(existing + keyed)
        }
    }
}
