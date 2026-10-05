package com.music.bitchord.ui

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.UserPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The account library, lifted out of the Android MainViewModel's library
 * section into the three pages the desktop sidebar actually has: songs
 * (liked + added), the account's playlists, and the recents list.
 *
 * Each page loads on first visit and keeps its state for the session, because
 * a sidebar is visited sideways — to the songs page, back out, over to
 * playlists — and a page that refetches on every visit flickers away what the
 * visitor was just looking at. Failed loads stay failed until a retry asks
 * again; nothing silently reloads.
 */
class LibraryViewModel(private val scope: CoroutineScope) {

    private val _songs = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val songs: StateFlow<UiState<List<Song>>> = _songs.asStateFlow()

    private val _playlists = MutableStateFlow<UiState<List<UserPlaylist>>>(UiState.Loading)
    val playlists: StateFlow<UiState<List<UserPlaylist>>> = _playlists.asStateFlow()

    private val _recents = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val recents: StateFlow<UiState<List<Song>>> = _recents.asStateFlow()

    private var songsLoaded = false
    private var playlistsLoaded = false
    private var recentsLoaded = false

    /** First page of liked + library songs. The sidebar's playlist section shares the playlists call. */
    fun ensureSongs() {
        if (songsLoaded) return
        songsLoaded = true
        scope.launch {
            YtMusicRepository.library()
                .onSuccess { page ->
                    _songs.value = UiState.Success(page.likedSongs + page.librarySongs)
                }
                .onFailure { failure ->
                    _songs.value = UiState.Error(failure.message ?: failure.javaClass.simpleName)
                }
        }
    }

    fun ensurePlaylists() {
        if (playlistsLoaded) return
        playlistsLoaded = true
        scope.launch {
            // The account's own playlists are the sidebar's list; the ones
            // YouTube saves for the account (its editorial subscriptions)
            // belong to the playlists page and arrive with it.
            YtMusicRepository.userPlaylists()
                .onSuccess { lists -> _playlists.value = UiState.Success(lists) }
                .onFailure { failure ->
                    _playlists.value = UiState.Error(failure.message ?: failure.javaClass.simpleName)
                }
        }
    }

    fun ensureRecents() {
        if (recentsLoaded) return
        recentsLoaded = true
        scope.launch {
            YtMusicRepository.recents()
                .onSuccess { songs -> _recents.value = UiState.Success(songs) }
                .onFailure { failure ->
                    _recents.value = UiState.Error(failure.message ?: failure.javaClass.simpleName)
                }
        }
    }

    fun retrySongs() {
        songsLoaded = false
        ensureSongs()
    }

    fun retryPlaylists() {
        playlistsLoaded = false
        ensurePlaylists()
    }

    fun retryRecents() {
        recentsLoaded = false
        ensureRecents()
    }
}
