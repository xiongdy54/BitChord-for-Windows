package com.music.bitchord.ui

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.shell.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What a detail page shows once its request has landed.
 *
 * [title]/[subtitle]/[thumbnailUrl] re-read what the fetched page says, because
 * the link that opened the page knows less than the page does — a shelf's card
 * carries no track count, and an artist card's subtitle is not always the
 * listeners line. They start as the link's values so the header is never
 * blank while the fetch runs.
 */
data class DetailUi(
    val title: String,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    val songs: List<Song> = emptyList(),
    val continuation: String? = null,
    /** Tracks YouTube offers to round a playlist out with, shown as their own section. */
    val suggested: List<Song> = emptyList(),
    /** Albums/singles carousels — an artist page's body below its top songs. */
    val sections: List<HomeShelf> = emptyList(),
    val description: String? = null,
    val monthlyListenersText: String? = null,
)

/**
 * One open detail page's state.
 *
 * Owned by [DetailPages], which keeps one instance per browse id for the
 * session: a back-and-forward between the same two albums must not refetch
 * them each leg, and a detail page is exactly the destination a user walks
 * away from and comes back to.
 */
class DetailViewModel(
    private val scope: CoroutineScope,
    private val destination: Destination.Detail,
) {

    private val _state = MutableStateFlow<UiState<DetailUi>>(UiState.Loading)
    val state: StateFlow<UiState<DetailUi>> = _state.asStateFlow()

    /** Which of the three page shapes to draw — the header reads it for its kind label. */
    val kind: BrowseType get() = destination.kind

    private var continuation: String? = null
    private var loadingMore = false

    init {
        load()
    }

    /** @return true while the page is mid-fetch, so a retry click is not doubled. */
    val isLoading: Boolean get() = _state.value is UiState.Loading

    fun retry() {
        if (!isLoading) load()
    }

    private fun load() {
        _state.value = UiState.Loading
        scope.launch {
            val result = when (destination.kind) {
                BrowseType.ARTIST -> YtMusicRepository.artistPage(destination.browseId)
                    .map { artist ->
                        DetailUi(
                            title = artist.name ?: destination.title,
                            subtitle = destination.subtitle,
                            thumbnailUrl = artist.thumbnailUrl ?: destination.thumbnailUrl,
                            songs = artist.songs,
                            sections = artist.sections,
                            description = artist.description,
                            monthlyListenersText = artist.monthlyListenerCount,
                        )
                    }

                else -> YtMusicRepository.browseSongs(destination.browseId)
                    .map { page ->
                        DetailUi(
                            title = page.header?.title ?: destination.title,
                            subtitle = page.header?.subtitle ?: destination.subtitle,
                            thumbnailUrl = page.header?.thumbnailUrl ?: destination.thumbnailUrl,
                            songs = page.songs,
                            continuation = page.continuation,
                            suggested = page.suggested,
                            description = page.description,
                        )
                    }
            }
            result.fold(
                onSuccess = { _state.value = UiState.Success(it) },
                onFailure = { failure ->
                    _state.value = UiState.Error(failure.message ?: failure.javaClass.simpleName)
                },
            )
        }
    }

    /**
     * The next page of an album or playlist's track listing — the one detail
     * surface that paginates. An artist's songs arrive whole, so the caller
     * simply never sees a continuation on those pages.
     */
    fun loadMore() {
        val token = continuation ?: return
        if (loadingMore) return
        loadingMore = true
        scope.launch {
            YtMusicRepository.moreSongs(token)
                .onSuccess { page ->
                    continuation = page.continuation
                    val current = (_state.value as? UiState.Success)?.data
                    if (current != null && page.songs.isNotEmpty()) {
                        _state.value = UiState.Success(
                            current.copy(
                                songs = current.songs + page.songs,
                                suggested = page.suggested.ifEmpty { current.suggested },
                            ),
                        )
                    }
                }
            loadingMore = false
        }
    }
}

/**
 * The session's open detail pages, one [DetailViewModel] per browse id.
 *
 * Created once in `Main.kt` and passed down, the way the other view models
 * are: a page's state must survive the navigation that left it, and a
 * `remember` keyed on the destination dies exactly when the user walks away —
 * the moment it was meant to hold on.
 */
class DetailPages(private val scope: CoroutineScope) {

    private val pages = HashMap<String, DetailViewModel>()

    fun viewModelFor(destination: Destination.Detail): DetailViewModel =
        pages.getOrPut(destination.browseId) { DetailViewModel(scope, destination) }
}
