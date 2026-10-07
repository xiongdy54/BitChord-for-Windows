package com.music.bitchord.ui.shell

import com.music.bitchord.data.model.BrowseType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every surface the content area can show.
 *
 * The detail payload carries what the *link* knew — the browse id plus whatever
 * the tapped card had for a title, a subtitle and a thumbnail — rather than a
 * page: the page is [com.music.bitchord.data.YtMusicRepository.browseSongs]'s
 * answer, fetched when the destination is mounted. A card's subtitle is not
 * always the page's artist ("单 poll" playlists bill their track count there),
 * so the detail header re-reads it off the fetched page and only falls back to
 * what arrived in the link.
 */
sealed interface Destination {
    data object Home : Destination
    data object Explore : Destination
    data object Search : Destination
    data object RecentlyAdded : Destination
    data object LibrarySongs : Destination
    data object LibraryPlaylists : Destination

    data class Detail(
        val kind: BrowseType,
        val browseId: String,
        val title: String,
        val subtitle: String? = null,
        val thumbnailUrl: String? = null,
    ) : Destination
}

/**
 * The back/forward machinery itself, without any Compose in it.
 *
 * Browser semantics, because that is what a desktop user reaches for: one list
 * of entries, one cursor. Opening truncates the forward tail (a sidebar click
 * is a fork, not a redo), [back] and [forward] walk the cursor, and opening the
 * entry that is already current is a no-op — clicking the sidebar row you are
 * on must not grow the history.
 */
class NavHistory(initial: Destination = Destination.Home) {

    private val entries = mutableListOf(initial)
    private var cursor = 0

    val current: Destination get() = entries[cursor]
    val canGoBack: Boolean get() = cursor > 0
    val canGoForward: Boolean get() = cursor < entries.lastIndex

    /** Entries behind the cursor, nearest first — for tests. */
    fun backEntries(): List<Destination> =
        entries.subList(0, cursor).reversed()

    /** Entries ahead of the cursor, nearest first — for tests. */
    fun forwardEntries(): List<Destination> =
        entries.subList(cursor + 1, entries.size).reversed()

    fun open(destination: Destination) {
        if (destination == current) return
        while (entries.size > cursor + 1) entries.removeAt(entries.lastIndex)
        entries += destination
        cursor = entries.lastIndex
    }

    /** @return whether the cursor moved — `false` with nothing behind it. */
    fun back(): Boolean {
        if (!canGoBack) return false
        cursor--
        return true
    }

    /** @return whether the cursor moved — `false` with nothing ahead of it. */
    fun forward(): Boolean {
        if (!canGoForward) return false
        cursor++
        return true
    }
}

/**
 * The content area's navigation, held outside every composable for the same
 * reason [ShellState] is: the window's own key handler reads it — Alt+←/→ walk
 * this stack — and a window key handler runs before the content exists to
 * remember anything.
 *
 * Every field is a separate flow rather than one snapshot object, because the
 * readers are independent: the toolbar's back/forward arrows watch the two
 * booleans, the content area watches [current], and a back that only moves the
 * cursor must not recompose a page whose destination did not change.
 */
class NavState(initial: Destination = Destination.Home) {

    private val history = NavHistory(initial)

    private val _current = MutableStateFlow(history.current)
    val current: StateFlow<Destination> = _current.asStateFlow()

    private val _canGoBack = MutableStateFlow(history.canGoBack)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(history.canGoForward)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    /**
     * Which way the last navigation moved, as the content area's transition
     * reads it: `+1` for forward (an open, or a walk ahead through the
     * stack), `-1` for back. Published alongside [current] so the page swap
     * and its direction land in the same snapshot — a transition that reads
     * one without the other slides the wrong way.
     */
    private val _movement = MutableStateFlow(1)
    val movement: StateFlow<Int> = _movement.asStateFlow()

    fun open(destination: Destination) {
        history.open(destination)
        _movement.value = 1
        publish()
    }

    /** @return whether the cursor moved, so a key handler knows to swallow the key. */
    fun goBack(): Boolean {
        val moved = history.back()
        if (moved) {
            _movement.value = -1
            publish()
        }
        return moved
    }

    fun goForward(): Boolean {
        val moved = history.forward()
        if (moved) {
            _movement.value = 1
            publish()
        }
        return moved
    }

    private fun publish() {
        _current.value = history.current
        _canGoBack.value = history.canGoBack
        _canGoForward.value = history.canGoForward
    }
}
