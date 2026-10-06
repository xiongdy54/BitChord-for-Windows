package com.music.bitchord.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.desktop.playback.PlayerController
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.recently_added
import com.music.bitchord.desktop.resources.songs
import com.music.bitchord.ui.DetailPages
import com.music.bitchord.ui.ExploreViewModel
import com.music.bitchord.ui.HomeViewModel
import com.music.bitchord.ui.LibraryViewModel
import com.music.bitchord.ui.SearchViewModel
import com.music.bitchord.ui.screens.DetailScreen
import com.music.bitchord.ui.screens.ExploreScreen
import com.music.bitchord.ui.screens.LibraryPlaylistsPage
import com.music.bitchord.ui.screens.LibrarySongsPage
import com.music.bitchord.ui.screens.RecentlyAddedPage
import com.music.bitchord.ui.screens.SettingsDialog
import org.jetbrains.compose.resources.stringResource

/**
 * Which of the player's two surfaces is up, and which row's menu is open.
 *
 * Lives outside every composable because the window's own key handler has to
 * reach it: [androidx.compose.ui.window.Window]'s `onPreviewKeyEvent` is a
 * parameter of the window, evaluated before its content, so it can read and
 * write this but nothing built inside the content lambda could. Esc closing
 * the player is the whole reason — see Main.kt.
 */
class ShellState {
    /** The full-screen player, over everything. */
    var showPlayer by mutableStateOf(false)

    /** The settings dialog, over the chrome but under the player. */
    var showSettings by mutableStateOf(false)

    /** The row whose action menu is up — the action sheet is a later slice. */
    var menuSong: Song? = null
}

/**
 * The app's chrome, the way Apple Music assembles it: a toolbar across the
 * top — navigation, transport, the now-playing display, volume, search — the
 * sidebar under it, and the content column beside that. Where the phone
 * layout floated a tab pill and a mini player over each page, both jobs now
 * belong to chrome that never overlaps the content: the sidebar navigates,
 * the toolbar carries the transport and the LCD, and no page has to reserve
 * room for what floats over it.
 *
 * The player, when [ShellState.showPlayer], is still the last child of the
 * same box, over everything, closed with Esc — slice 2's overlay unchanged,
 * because a full-screen player is not a phone idea and survived the redesign
 * untouched.
 */
@Composable
fun Shell(
    player: PlayerController,
    home: HomeViewModel,
    search: SearchViewModel,
    library: LibraryViewModel,
    explore: ExploreViewModel,
    detailPages: DetailPages,
    nav: NavState,
    state: ShellState,
    windowMaximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    /** The frame — the toolbar's drag gesture moves it. */
    window: java.awt.Window,
    initialQuery: String = "",
    autoPlayFirst: Boolean = false,
    autoOpenPlayer: Boolean = false,
) {
    // One snapshot for the whole chrome. The playhead is not in it, so a tick
    // still recomposes the scrubber alone; the toolbar shows no position.
    val snapshot by player.state.collectAsState()
    val status by player.status.collectAsState()
    val song = snapshot.song

    val current by nav.current.collectAsState()
    val canGoBack by nav.canGoBack.collectAsState()
    val canGoForward by nav.canGoForward.collectAsState()
    val playlistsState by library.playlists.collectAsState()
    val query by search.query.collectAsState()
    val shuffle by player.shuffleEnabled.collectAsState()

    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val mode = sidebarMode(windowWidth)
    val playlists = (playlistsState as? UiState.Success)?.data.orEmpty()

    // Debug hook — see Main.kt. Opens the player the first time there is a track
    // to open it on, so the full-screen branch can be screenshotted without a hand
    // on the mouse. Keyed on `song?.videoId`, so it re-arms once per track.
    LaunchedEffect(autoOpenPlayer, song?.videoId) {
        if (autoOpenPlayer && song != null) state.showPlayer = true
    }

    // The sidebar's playlist section is the one thing the chrome itself needs:
    // load it once, quietly, whether or not the playlists page is ever opened.
    LaunchedEffect(Unit) { library.ensurePlaylists() }

    // The playback source titles the content router hands the player. Read
    // here, once, rather than in each page that needs one.
    val songsSourceTitle = stringResource(Res.string.songs)
    val recentsSourceTitle = stringResource(Res.string.recently_added)

    Column(
        Modifier
            .fillMaxSize()
            // Deliberately unpainted: the window is transparent over the
            // DWM backdrop (Mica), and the chrome paints at partial alpha
            // to let it through. Only the content column is solid.
            // Space toggles playback from anywhere that did not consume it —
            // a focused button or text field eats the key first, so the
            // bubble phase is what keeps this off the search field's typing.
            .onKeyEvent { event ->
                if (spaceTogglesPlayback(event.key, event.type)) {
                    player.togglePlayPause()
                    true
                } else {
                    false
                }
            },
    ) {
        // The toolbar spans the full window width, the sidebar starting
        // below it — the undecorated window has no title bar of its own, so
        // this row *is* the top of the frame: drag area, transport, and the
        // window's three buttons at its end.
        Toolbar(
            songTitle = song?.title,
            songArtist = song?.artist,
            songThumbnailUrl = song?.thumbnailUrl,
            isPlaying = snapshot.isPlaying,
            status = status,
            shuffleEnabled = shuffle,
            repeatMode = snapshot.repeatMode,
            volumePercent = volumePercent(player),
            onVolumeChange = player::setVolume,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            onBack = nav::goBack,
            onForward = nav::goForward,
            onToggleShuffle = player::toggleShuffle,
            onPrevious = player::previous,
            onPlayPause = player::togglePlayPause,
            onNext = player::next,
            onCycleRepeat = player::cycleRepeat,
            onOpenPlayer = { if (song != null) state.showPlayer = true },
            windowMaximized = windowMaximized,
            onMinimize = onMinimize,
            onToggleMaximize = onToggleMaximize,
            onClose = onClose,
            window = window,
            modifier = Modifier.background(chromeColor()),
        )
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Sidebar(
                current = current,
                playlists = playlists,
                mode = mode,
                onSelect = nav::open,
                query = query,
                onQueryChange = search::onQueryChange,
                onSearchFocus = { nav.open(Destination.Search) },
                onOpenSettings = { state.showSettings = true },
                modifier = Modifier.background(chromeColor()),
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The one solid surface: the backdrop lives behind the
                    // chrome, the pages live on their own colour.
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    Modifier
                        .widthIn(max = CONTENT_MAX_WIDTH)
                        .fillMaxWidth(),
                ) {
                    when (val destination = current) {
                        Destination.Home -> HomePage(
                            vm = home,
                            player = player,
                            onOpenDetail = nav::open,
                            autoPlayFirst = autoPlayFirst,
                        )

                        Destination.Explore -> ExploreScreen(
                            viewModel = explore,
                            onOpenGenre = explore::openGenre,
                            onShelfItemClick = { item ->
                                openShelfItem(item, player, onOpenDetail = nav::open)
                            },
                        )

                        Destination.Search -> SearchPage(
                            vm = search,
                            player = player,
                            nav = nav,
                            initialQuery = initialQuery,
                            autoPlayFirst = autoPlayFirst,
                        )

                        Destination.RecentlyAdded -> RecentlyAddedPage(
                            viewModel = library,
                            onPlayFrom = { songs, index ->
                                player.playFrom(
                                    songs,
                                    index,
                                    PlaybackSourceType.HISTORY,
                                    recentsSourceTitle,
                                    null,
                                )
                            },
                        )

                        Destination.LibrarySongs -> LibrarySongsPage(
                            viewModel = library,
                            onPlayFrom = { songs, index ->
                                player.playFrom(
                                    songs,
                                    index,
                                    PlaybackSourceType.BROWSE,
                                    songsSourceTitle,
                                    null,
                                )
                            },
                        )

                        Destination.LibraryPlaylists -> LibraryPlaylistsPage(
                            viewModel = library,
                            onOpenPlaylist = { playlist ->
                                nav.open(
                                    Destination.Detail(
                                        kind = BrowseType.PLAYLIST,
                                        browseId = playlist.browseId,
                                        title = playlist.title,
                                        subtitle = playlist.subtitle,
                                        thumbnailUrl = playlist.thumbnailUrl,
                                    ),
                                )
                            },
                        )

                        is Destination.Detail -> DetailRoute(
                            destination = destination,
                            detailPages = detailPages,
                            player = player,
                            nav = nav,
                        )
                    }
                }
            }
        }
    }

    if (state.showSettings) {
        SettingsDialog(onDismiss = { state.showSettings = false })
    }

    // The player paints last, over the chrome. It is deliberately not
    // registered as a haze source — it brings its own gradient backdrop, and a
    // second frost over the content column is how a ghost of the sidebar ends
    // up showing through the sleeve. Covered is not the same as unreachable:
    // Compose hit-traversal only offers a region to the layers that own a
    // pointer node, and the player's root boxes own none of their own, so the
    // consumer below swallows every press no control claims. Without it, a
    // press in any dead area of the full-screen player falls straight through
    // to the chrome painted beneath it. The consumer sits on the *parent*, so
    // the player's own controls still get their presses first. Swallowing at
    // the overlay, instead of not mounting the chrome beneath it, is
    // deliberate: unmounting the toolbar to make the player modal would make
    // the transport vanish for the frame the overlay closes on.
    if (state.showPlayer) {
        PlayerPage(
            player = player,
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                    }
                },
        )
    }
}

/**
 * One detail destination, bound to its session-scoped view model. The list
 * state is remembered per browse id too, so coming back to an album you
 * scrolled lands where you left it — the same reason the view model survives.
 */
@Composable
private fun DetailRoute(
    destination: Destination.Detail,
    detailPages: DetailPages,
    player: PlayerController,
    nav: NavState,
) {
    val viewModel = remember(destination.browseId) { detailPages.viewModelFor(destination) }
    val listState = remember(destination.browseId) { LazyListState() }
    DetailScreen(
        viewModel = viewModel,
        listState = listState,
        onPlayFrom = { songs, index ->
            player.playFrom(
                songs,
                index,
                PlaybackSourceType.BROWSE,
                destination.title,
                destination.browseId,
            )
        },
        onShufflePlay = { songs ->
            // Both halves post onto the player's one queue dispatcher, so the
            // shuffle is applied before the queue is built — the FIFO is the
            // ordering guarantee, not a happens-before anyone can see.
            player.toggleShuffle()
            player.playFrom(
                songs,
                0,
                PlaybackSourceType.BROWSE,
                destination.title,
                destination.browseId,
            )
        },
        onShelfItemClick = { item -> openShelfItem(item, player, onOpenDetail = nav::open) },
    )
}

/**
 * The chrome's own colour: the surface tone at partial alpha, so the DWM
 * backdrop behind the transparent window reads through it — the window
 * material doing what a flat fill used to.
 *
 * How much it lets through is a per-theme decision, because the backdrop's
 * own colour is not ours to know — Mica follows the desktop wallpaper. A
 * light chrome over a light backdrop can afford the full vibrancy; a dark
 * chrome must stay dark whatever sits behind it, or the wallpaper's tone
 * leaks into the navigation and the toolbar turns the wallpaper's grey. So
 * dark mode composites near-opaque and leaves Mica only a whisper of life.
 */
@Composable
private fun chromeColor(): Color {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val alpha = if (base.luminance() < 0.5f) CHROME_ALPHA_DARK else CHROME_ALPHA_LIGHT
    return base.copy(alpha = alpha)
}

/** The light chrome's share of the backdrop. Apple's app sits near seven tenths. */
private const val CHROME_ALPHA_LIGHT = 0.72f

/** The dark chrome's share — high enough that no wallpaper can grey it out. */
private const val CHROME_ALPHA_DARK = 0.92f

/**
 * The window's space rule: `true` when this event is the one that toggles
 * playback. Acted on the press, ignored on the release — a text field or a
 * focused button consumes the press, and anything that let it through has
 * already agreed the space bar meant nothing to it.
 */
internal fun spaceTogglesPlayback(key: Key, type: KeyEventType): Boolean =
    key == Key.Spacebar && type == KeyEventType.KeyDown

@Composable
private fun volumePercent(player: PlayerController): Int {
    val volume by player.volume.collectAsState()
    return volume
}
