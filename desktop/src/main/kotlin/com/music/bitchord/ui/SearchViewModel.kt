package com.music.bitchord.ui

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Search, lifted out of the Android MainViewModel's search section
 * (`app/.../ui/MainViewModel.kt`): the same split between what the field is
 * doing and what has been asked for, so typing suggests and only a submit
 * searches; the same guard that drops a response the field has moved past; and
 * the same two debounced pipelines rather than a request per keystroke.
 *
 * Two simplifications. History is a session list rather than a database table —
 * the Android side persists it, and storage comes with the settings slice. And
 * with no sign-in there is no per-account cache to keep, so a search is made
 * when it is asked for and the rows stand until the next one.
 */
class SearchViewModel(private val scope: CoroutineScope) {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    /** Null until a search has been asked for — the field is suggesting, not answering. */
    private val _results = MutableStateFlow<UiState<List<SearchResult>>?>(null)
    val results: StateFlow<UiState<List<SearchResult>>?> = _results.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _typeaheadResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val typeaheadResults: StateFlow<List<SearchResult>> = _typeaheadResults.asStateFlow()

    private val _history = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())
    val history: StateFlow<List<SearchHistoryEntity>> = _history.asStateFlow()

    /** Bumped on every new search so the list scrolls back to the top for it. */
    private val _scrollResetTrigger = MutableStateFlow(0)
    val scrollResetTrigger: StateFlow<Int> = _scrollResetTrigger.asStateFlow()

    private var continuation: String? = null

    /** True once Enter (or a history row, or a filter) has asked for results. */
    private var submitted = false

    /** Incremented per search; a response whose id is stale is dropped. */
    private var newestRequestId = 0L

    @OptIn(FlowPreview::class)
    private fun startSuggestPipeline() = scope.launch {
        _query.debounce(SUGGEST_DEBOUNCE_MS).collectLatest { input ->
            if (input.isBlank() || submitted) return@collectLatest
            val fetched = YtMusicRepository.searchSuggestions(input).getOrNull() ?: return@collectLatest
            // Asked again on the way back: the field is live throughout.
            if (_query.value != input || submitted) return@collectLatest
            _suggestions.value = listOf(input) +
                fetched.filterNot { it.equals(input, ignoreCase = true) }
        }
    }

    @OptIn(FlowPreview::class)
    private fun startTypeaheadPipeline() = scope.launch {
        _query.debounce(TYPEAHEAD_DEBOUNCE_MS).collectLatest { input ->
            if (input.isBlank() || submitted) {
                _typeaheadResults.value = emptyList()
                return@collectLatest
            }
            val rows = YtMusicRepository.searchTypeahead(input).getOrNull()?.rows.orEmpty()
            if (_query.value == input && !submitted) _typeaheadResults.value = rows
        }
    }

    init {
        startSuggestPipeline()
        startTypeaheadPipeline()
    }

    fun onQueryChange(text: String) {
        _query.value = text
        // Typing again after a search goes back to suggesting.
        submitted = false
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        if (text.isBlank()) {
            _results.value = null
            continuation = null
        }
    }

    fun onFilterChange(filter: SearchFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        // A filter is a question about results, so it only means something once
        // results have been asked for.
        if (submitted || _results.value != null) {
            submitted = true
            search()
        }
    }

    fun submit() {
        if (_query.value.isBlank()) return
        submitted = true
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        search()
    }

    /** A history row or suggestion tapped: the text is the query. */
    fun onSubmitText(text: String) {
        _query.value = text
        submit()
    }

    private fun search() {
        val request = ++newestRequestId
        val query = _query.value
        val filter = _filter.value
        continuation = null
        _loadingMore.value = false
        _results.value = UiState.Loading
        _scrollResetTrigger.value = _scrollResetTrigger.value + 1
        scope.launch {
            val result = YtMusicRepository.searchPage(query, filter)
            // A search the field has moved past never lands on screen.
            if (request != newestRequestId) return@launch
            _results.value = result.fold(
                onSuccess = { page ->
                    continuation = page.continuation
                    UiState.Success(page.rows)
                },
                onFailure = { UiState.Error(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    fun loadMore() {
        val token = continuation ?: return
        if (_loadingMore.value) return
        _loadingMore.value = true
        val request = newestRequestId
        val filter = _filter.value
        scope.launch {
            YtMusicRepository.searchContinuation(token, filter)
                .onSuccess { page ->
                    if (request != newestRequestId) return@onSuccess
                    continuation = page.continuation
                    val current = (_results.value as? UiState.Success)?.data.orEmpty()
                    _results.value = UiState.Success(
                        (current + page.rows).distinctBy(::identityKey),
                    )
                }
            _loadingMore.value = false
        }
    }

    // ── History ─────────────────────────────────────────────────────────────

    /** Records what was tapped, newest first, one row per entity. */
    fun record(entity: SearchHistoryEntity) {
        _history.value = (listOf(entity) + _history.value.filterNot { it.id == entity.id })
            .take(HISTORY_LIMIT)
    }

    fun removeHistory(id: String) {
        _history.value = _history.value.filterNot { it.id == id }
    }

    fun clearHistory() {
        _history.value = emptyList()
    }

    private companion object {
        /** Long enough that a burst of keystrokes is one request, short enough to feel live. */
        const val SUGGEST_DEBOUNCE_MS = 220L

        /** A full page is behind this one, so it waits longer than the suggestion list. */
        const val TYPEAHEAD_DEBOUNCE_MS = 350L

        const val HISTORY_LIMIT = 20

        /**
         * The same identity the repository dedupes a page by (it keeps its own
         * copy private): a track is its videoId, a collection its browseId.
         */
        fun identityKey(row: SearchResult): String = when (row) {
            is SearchResult.TopTrack -> "v:${row.song.videoId}"
            is SearchResult.Track -> "v:${row.song.videoId}"
            is SearchResult.Browse -> "b:${row.item.browseId}"
        }
    }
}
