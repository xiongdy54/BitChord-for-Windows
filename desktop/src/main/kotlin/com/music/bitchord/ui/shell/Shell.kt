package com.music.bitchord.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
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
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.glass.LocalAppBackdrop
import com.music.bitchord.ui.glass.rememberAppBackdrop
import com.music.bitchord.ui.glass.layerBackdrop
import com.music.bitchord.auth.AccountController
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.auth.YtMusicLoginWindow
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.desktop.playback.PlayerController
import com.music.bitchord.download.DownloadSession
import com.music.bitchord.download.Downloads
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.history
import com.music.bitchord.desktop.resources.local_music
import com.music.bitchord.desktop.resources.recently_added
import com.music.bitchord.desktop.resources.songs
import com.music.bitchord.ui.components.DownloadManagerPanel
import com.music.bitchord.ui.DetailPages
import com.music.bitchord.ui.ExploreViewModel
import com.music.bitchord.ui.HomeViewModel
import com.music.bitchord.ui.LibraryViewModel
import com.music.bitchord.ui.SearchViewModel
import com.music.bitchord.ui.screens.DetailScreen
import com.music.bitchord.ui.screens.ExploreScreen
import com.music.bitchord.ui.screens.HistoryPage
import com.music.bitchord.ui.screens.LibraryPlaylistsPage
import com.music.bitchord.ui.screens.LibrarySongsPage
import com.music.bitchord.desktop.playback.LyricsCoordinator
import com.music.bitchord.ui.screens.DownloadsPage
import com.music.bitchord.ui.screens.LocalMusicPage
import com.music.bitchord.ui.screens.RecentlyAddedPage
import com.music.bitchord.ui.screens.SettingsDialog
import com.music.bitchord.ui.player.PlayerContextMenuArea
import com.music.bitchord.ui.player.SleepTimerDialog
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

    /**
     * The lyric sheet inside the full-screen player. Window-level for the same
     * reason [showPlayer] is: Escape has to close the sheet before it closes
     * the player, and the key handler reads this before any content exists.
     */
    var lyricsOpen by mutableStateOf(false)

    /**
     * The sleep timer's picker — window-level like [showSettings], because it
     * opens from the player's right-click menu, which lives over everything.
     */
    var showSleepTimer by mutableStateOf(false)

    /**
     * The download manager's panel — window-level like [showSettings], reached
     * from the toolbar's own download indicator. Marking the batch seen is the
     * *host's* act, not the panel's: it belongs to this flag going down, so
     * every way out of the panel signs off on the result.
     */
    var showDownloadsManager by mutableStateOf(false)

    /**
     * The Google sign-in window — window-level like [showSettings], for the
     * same airspace reason it exists at all: a heavyweight Chromium child
     * window cannot layer over the transparent Skia chrome without tearing,
     * so the login is an independent modal [androidx.compose.ui.window.Dialog]
     * hosted beside the settings dialog rather than inside it.
     */
    var showLogin by mutableStateOf(false)

    /** What the login window is for; set together with [showLogin]. */
    var loginMode by mutableStateOf(WebSessionMode.SIGN_IN)
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
    lyrics: LyricsCoordinator,
    home: HomeViewModel,
    search: SearchViewModel,
    library: LibraryViewModel,
    explore: ExploreViewModel,
    detailPages: DetailPages,
    nav: NavState,
    state: ShellState,
    /** The session registry — the account group's reads and acts route through it. */
    accounts: AccountController,
    /** Opens the Google sign-in window in the given mode. */
    onOpenLogin: (WebSessionMode) -> Unit,
    /** The settings switch this chrome paints for — partial alpha over Mica, or solid. */
    translucentWindow: Boolean,
    windowMaximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    /** The frame — the toolbar's drag gesture moves it. */
    window: java.awt.Window,
    initialQuery: String = "",
    autoPlayFirst: Boolean = false,
    autoOpenPlayer: Boolean = false,
    autoOpenLyrics: Boolean = false,
    /** The screenshot pass's way into the output picker, which lives inside settings. */
    autoOpenAudioOutput: Boolean = false,
    /** The screenshot pass's way into the equalizer, which lives inside settings too. */
    autoOpenEqualizer: Boolean = false,
    /** The screenshot pass's way into the source editor, which lives inside settings too. */
    autoOpenSourceEditor: Boolean = false,
    /** The screenshot pass's way to the bottom groups of the settings dialog. */
    scrollSettingsToEnd: Boolean = false,
) {
    // One snapshot for the whole chrome. The playhead is not in it, so a tick
    // still recomposes the scrubber alone; the toolbar shows no position.
    val snapshot by player.state.collectAsState()
    val status by player.status.collectAsState()
    val song = snapshot.song

    val current by nav.current.collectAsState()
    val canGoBack by nav.canGoBack.collectAsState()
    val canGoForward by nav.canGoForward.collectAsState()
    // Which way the stack last moved — the content transition reads it to
    // slide the pages the way the navigation went.
    val movement by nav.movement.collectAsState()
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
    // The sheet's own half of the same hook, one surface deeper: keyed the
    // same way, gated on the player it lives inside.
    LaunchedEffect(autoOpenLyrics, song?.videoId) {
        if (autoOpenLyrics && song != null && state.showPlayer) state.lyricsOpen = true
    }

    // The sidebar's playlist section is the one thing the chrome itself needs:
    // load it once, quietly, whether or not the playlists page is ever opened.
    LaunchedEffect(Unit) { library.ensurePlaylists() }

    // The playback source titles the content router hands the player. Read
    // here, once, rather than in each page that needs one.
    val songsSourceTitle = stringResource(Res.string.songs)
    val recentsSourceTitle = stringResource(Res.string.recently_added)
    val historySourceTitle = stringResource(Res.string.history)
    val localMusicSourceTitle = stringResource(Res.string.local_music)

    Column(
        Modifier
            .fillMaxSize()
        // Unpainted either way: with the translucent window on, the chrome at
        // partial alpha is what lets the DWM backdrop (Mica) through; with it
        // off, the chrome paints solid and this column stays bare beneath the
        // same children that cover it completely.
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
            onOpenDownloadsManager = { state.showDownloadsManager = true },
            windowMaximized = windowMaximized,
            onToggleMaximize = onToggleMaximize,
            window = window,
            modifier = Modifier.background(chromeColor(translucentWindow)),
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
                modifier = Modifier.background(chromeColor(translucentWindow)),
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
                AnimatedContent(
                    targetState = current,
                    transitionSpec = {
                        // The navigation's own direction, mirrored in the
                        // pages: forward, the new page eases in from the
                        // right while the old accelerates away left; back,
                        // the mirror. Both small — a few percent of the
                        // width, under the fade — because this is wayfinding,
                        // not theatre: a nudge that says where the page came
                        // from, over before it can be in the way (exit
                        // ease-in and shorter than the ease-in entrance, the
                        // asymmetry that makes the swap read as motion
                        // rather than a blink).
                        val forward = movement >= 0
                        val enter = slideInHorizontally(
                            animationSpec = tween(PAGE_TRANSITION_ENTER_MS, easing = EASE_OUT),
                        ) { full -> if (forward) full / 26 else -full / 26 } +
                            fadeIn(tween(PAGE_TRANSITION_ENTER_MS, easing = EASE_OUT))
                        val exit = slideOutHorizontally(
                            animationSpec = tween(PAGE_TRANSITION_EXIT_MS, easing = EASE_IN),
                        ) { full -> if (forward) -full / 26 else full / 26 } +
                            fadeOut(tween(PAGE_TRANSITION_EXIT_MS, easing = EASE_IN))
                        enter togetherWith exit
                    },
                    label = "contentRoute",
                    modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth(),
                ) { destination ->
                    when (destination) {
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

                        Destination.History -> HistoryPage(
                            viewModel = library,
                            onPlayFrom = { songs, index ->
                                player.playFrom(
                                    songs,
                                    index,
                                    PlaybackSourceType.HISTORY,
                                    historySourceTitle,
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

                        Destination.Downloads -> DownloadsPage(
                            onPlayFrom = { songs, index ->
                                // The queue plays straight off the files: the
                                // resolve seam finds each saved row on disk and
                                // never asks the network for it.
                                player.playFrom(
                                    songs,
                                    index,
                                    PlaybackSourceType.BROWSE,
                                    songsSourceTitle,
                                    null,
                                )
                            },
                        )

                        Destination.LocalMusic -> LocalMusicPage(
                            // The third argument names the queue's source id —
                            // `local:all` or `local:webdav`, the browse ids the
                            // page's two sources address their rows by.
                            onPlayFrom = { songs, index, sourceId ->
                                player.playFrom(
                                    songs,
                                    index,
                                    PlaybackSourceType.BROWSE,
                                    localMusicSourceTitle,
                                    sourceId,
                                )
                            },
                            currentSong = song,
                            isPlaying = snapshot.isPlaying,
                            onOpenSettings = { state.showSettings = true },
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
        SettingsDialog(
            onDismiss = { state.showSettings = false },
            onAudioQualityChanged = player::setAudioQuality,
            accountController = accounts,
            onOpenLogin = onOpenLogin,
            // The output device's enumeration is the engine's own native call,
            // handed over as a lambda so the dialog stays a surface and the
            // switch lands in the same place every other audio act does.
            enumerateAudioOutputDevices = { player.audioOutputDevices() },
            onAudioOutputDeviceChanged = { device ->
                player.setAudioOutputDevice(device?.id, device?.name)
            },
            // One-shot: the flag is only ever true from the screenshot probe,
            // which wants the settings dialog up with its child open.
            autoOpenAudioOutput = autoOpenAudioOutput,
            autoOpenEqualizer = autoOpenEqualizer,
            autoOpenSourceEditor = autoOpenSourceEditor,
            scrollToEnd = scrollSettingsToEnd,
        )
    }

    // The sign-in window, beside the settings dialog rather than inside it:
    // its browser is a native child window the settings dialog's surface
    // cannot host. SWITCH_CHANNEL opens on the active account's own cookie
    // snapshot — the picker must show that account's Google login, not
    // whichever one signed in most recently in the browser's jar.
    if (state.showLogin) {
        val mode = state.loginMode
        YtMusicLoginWindow(
            mode = mode,
            initialCookie = if (mode == WebSessionMode.SWITCH_CHANNEL) {
                accounts.accounts.value.firstOrNull {
                    it.accountId == accounts.activeAccountId.value
                }?.cookie
            } else {
                null
            },
            captureRequest = 0,
            onPageReady = {},
            onCaptureUnavailable = {},
            onCaptured = { session ->
                // Validation-first, inside the controller: the window only
                // closes once the session is accepted, so a rejected capture
                // leaves the page up for another try.
                accounts.onWebSession(session, mode) { accepted ->
                    if (accepted) state.showLogin = false
                }
            },
            onDismiss = { state.showLogin = false },
        )
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
        // The swallow sits on the *outside*, the context-menu area inside it:
        // the main pass hands events to the deepest node first, so the menu's
        // detector is the first thing a right-click reaches and no amount of
        // swallowing behind it can starve it — the guaranteed shape, where an
        // ancestor area beside a swallowing descendant would depend on every
        // consumer in between staying primary-only. The outer swallow keeps
        // the fall-through rule whole: a dead-area press (either button now)
        // never reaches the chrome painted underneath, and the player's own
        // controls — deeper still than the menu area — get their presses
        // before either. Swallowing at the overlay, instead of not mounting
        // the chrome beneath it, stays deliberate: unmounting the toolbar to
        // make the player modal would make the transport vanish for the frame
        // the overlay closes on.
        //
        // The glass's view of the world (slice 17): the player's own content
        // recorded for the bottom controls' glass to sample. Recorded only
        // while the glass is wanted — the recorder is one extra full-content
        // capture per frame, and upstream gates it the same way
        // (glassSamplesBackdrop).
        val liquidGlassWanted by AppSettings.liquidGlass.collectAsState()
        val reduceBlur by AppSettings.reduceDynamicBlur.collectAsState()
        val playerBackdrop = rememberAppBackdrop()
        CompositionLocalProvider(LocalAppBackdrop provides playerBackdrop) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                    }
                },
        ) {
            PlayerContextMenuArea(
                song = song,
                onOpenSleepTimer = { state.showSleepTimer = true },
            ) {
                PlayerPage(
                    player = player,
                    lyrics = lyrics,
                    state = state,
                    // The player overlay paints over every page, so a tap that means
                    // "take me to that album" has to dismiss it on the way through —
                    // the detail page opens underneath and the overlay would only
                    // keep the user from seeing it.
                    onOpenAlbum = { browseId ->
                        song?.let {
                            nav.open(
                                Destination.Detail(
                                    kind = BrowseType.ALBUM,
                                    browseId = browseId,
                                    title = it.albumName ?: it.title,
                                    subtitle = it.artist,
                                    thumbnailUrl = it.thumbnailUrl,
                                ),
                            )
                            state.showPlayer = false
                        }
                    },
                    onOpenArtist = { browseId ->
                        song?.let {
                            nav.open(
                                Destination.Detail(
                                    kind = BrowseType.ARTIST,
                                    browseId = browseId,
                                    title = it.artist,
                                    subtitle = null,
                                    thumbnailUrl = it.thumbnailUrl,
                                ),
                            )
                            state.showPlayer = false
                        }
                    },
                    modifier = Modifier.fillMaxSize().then(
                        if (liquidGlassWanted && !reduceBlur) {
                            Modifier.layerBackdrop(playerBackdrop)
                        } else {
                            Modifier
                        },
                    ),
                )
            }
        }
        }
    }

    // The sleep timer's picker, over everything the player lives over: it is
    // reached from the player's menu, and it is a window-level dialog like
    // settings — not a layer of the player itself.
    if (state.showSleepTimer) {
        SleepTimerDialog(onDismiss = { state.showSleepTimer = false })
    }

    // The download manager, a window-level dialog like the sleep timer — the
    // desktop counterpart of the phone's global download sheet. The batch is
    // marked seen on the way *out* (upstream's close callback did the same):
    // what the exit signs off on is the result, so a look in halfway through a
    // running batch signs nothing, and the indicator stays up until the queue
    // has finished and been read.
    if (state.showDownloadsManager) {
        DownloadManagerPanel(
            onDismiss = {
                state.showDownloadsManager = false
                DownloadSession.markSeen()
            },
        )
    }

    // The window's own three, painted over everything — player included.
    // They left the toolbar for exactly this reason: chrome that disappears
    // with the full-screen player takes the close button with it. The layer
    // itself registers no pointer input, so every press that is not on one
    // of the three buttons falls through to the player or the chrome below.
    Box(Modifier.fillMaxSize()) {
        WindowControls(
            maximized = windowMaximized,
            overPlayer = state.showPlayer,
            onMinimize = onMinimize,
            onToggleMaximize = onToggleMaximize,
            onClose = onClose,
            modifier = Modifier.align(Alignment.TopEnd).height(TOOLBAR_HEIGHT),
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
        onDownloadAll = { songs ->
            // The batch ask (slice 20): the release is recorded as the asker —
            // the header pill reads [Downloads.requested] to say where its own
            // batch stands — and every row goes into the queue under it. The
            // enqueue's own dedupe makes a second tap on a partially-landed
            // release a top-up rather than a doubling.
            Downloads.markRequested(destination.browseId, songs.map { it.videoId })
            songs.forEach { Downloads.enqueue(it, destination.browseId) }
        },
    )
}

/**
 * The chrome's own colour: the surface tone at partial alpha, so the DWM
 * backdrop behind the transparent window reads through it — the window
 * material doing what a flat fill used to. The opaque mode the settings
 * switch selects has no backdrop behind it to read through, so there the
 * tone paints solid: partial alpha over nothing would just blend the chrome
 * with whatever the frame's own background colour is.
 *
 * How much it lets through is a per-theme decision, because the backdrop's
 * own colour is not ours to know — Mica follows the desktop wallpaper. A
 * light chrome over a light backdrop can afford the full vibrancy; a dark
 * chrome must stay dark whatever sits behind it, or the wallpaper's tone
 * leaks into the navigation and the toolbar turns the wallpaper's grey. So
 * dark mode composites near-opaque and leaves Mica only a whisper of life.
 */
@Composable
private fun chromeColor(translucent: Boolean): Color {
    val base = MaterialTheme.colorScheme.surfaceVariant
    if (!translucent) return base
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
