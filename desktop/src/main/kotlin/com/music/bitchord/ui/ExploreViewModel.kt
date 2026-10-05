package com.music.bitchord.ui

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The browse side of YouTube Music: the moods-and-genres grid, and the shelves
 * one of them opens into.
 *
 * The section list loads once per session — the grid is small and changes
 * slowly — while a genre's shelves load on every open, keyed by the genre's
 * own browse id so a second visit to the one you just left does not refetch
 * what is still on screen behind you.
 */
class ExploreViewModel(private val scope: CoroutineScope) {

    private val _sections = MutableStateFlow<UiState<List<MoodGenreSection>>>(UiState.Loading)
    val sections: StateFlow<UiState<List<MoodGenreSection>>> = _sections.asStateFlow()

    /** The shelves of the genre being read, and the genre itself for the page header. */
    private val _genreShelves = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val genreShelves: StateFlow<UiState<List<HomeShelf>>> = _genreShelves.asStateFlow()

    private val _openGenre = MutableStateFlow<MoodGenre?>(null)
    val openGenre: StateFlow<MoodGenre?> = _openGenre.asStateFlow()

    private var sectionsLoaded = false
    private val shelfCache = HashMap<String, List<HomeShelf>>()

    fun ensureSections() {
        if (sectionsLoaded) return
        sectionsLoaded = true
        scope.launch {
            YtMusicRepository.moodAndGenres()
                .onSuccess { sections -> _sections.value = UiState.Success(sections) }
                .onFailure { failure ->
                    _sections.value = UiState.Error(failure.message ?: failure.javaClass.simpleName)
                }
        }
    }

    fun retrySections() {
        sectionsLoaded = false
        ensureSections()
    }

    fun openGenre(genre: MoodGenre) {
        _openGenre.value = genre
        val key = "${genre.browseId}:${genre.params.orEmpty()}"
        val cached = shelfCache[key]
        if (cached != null) {
            _genreShelves.value = UiState.Success(cached)
            return
        }
        _genreShelves.value = UiState.Loading
        scope.launch {
            YtMusicRepository.moodGenreShelves(genre.browseId, genre.params)
                .onSuccess { shelves ->
                    shelfCache[key] = shelves
                    // A second genre may have been opened while this one was
                    // out; only the still-open one gets to draw.
                    if (_openGenre.value == genre) _genreShelves.value = UiState.Success(shelves)
                }
                .onFailure { failure ->
                    if (_openGenre.value == genre) {
                        _genreShelves.value =
                            UiState.Error(failure.message ?: failure.javaClass.simpleName)
                    }
                }
        }
    }

    /** Back out of a genre to the grid, clearing the shelves the genre left up. */
    fun closeGenre() {
        _openGenre.value = null
        _genreShelves.value = UiState.Loading
    }
}
