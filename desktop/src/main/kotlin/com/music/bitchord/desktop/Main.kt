package com.music.bitchord.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.music.bitchord.data.AppFiles
import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.FileStore
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.desktop.playback.PlayerController
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.ui.DetailPages
import com.music.bitchord.ui.ExploreViewModel
import com.music.bitchord.ui.HomeViewModel
import com.music.bitchord.ui.LibraryViewModel
import com.music.bitchord.ui.SearchViewModel
import com.music.bitchord.ui.components.BitChordContextMenuRepresentation
import com.music.bitchord.ui.components.BitChordMenuPanel
import com.music.bitchord.ui.components.LocalSongActions
import com.music.bitchord.ui.components.SongActions
import com.music.bitchord.ui.components.rememberSongActionLabels
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.foundation.LocalContextMenuRepresentation
import com.music.bitchord.ui.shell.Destination
import com.music.bitchord.ui.shell.NavState
import com.music.bitchord.ui.shell.Shell
import com.music.bitchord.ui.shell.ShellState
import com.music.bitchord.ui.shell.WindowGeometry
import com.music.bitchord.ui.theme.BitChordTheme
import com.music.bitchord.ui.theme.resolveDarkTheme
import com.music.bitchord.ui.shell.toggleSongLike
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.util.Locale

/*
 * Debug hooks, off by default. They exist so the window can be driven without
 * a hand on the keyboard — the job Probe does for the data layer, one level
 * up — which is also how the desktop build gets smoke-tested:
 *
 *   ./gradlew -p desktop run -Pbitchord.probeQuery="周杰伦 晴天" \
 *       -Pbitchord.probeAutoplay=true -Pbitchord.probeOpenPlayer=true -Pbitchord.autoExitMs=25000
 *
 * `probeOpenPlayer` opens the full-screen player as soon as there is a track, which is how
 * the player's screenshots are taken at all: nothing in this build reaches the player except
 * a click, and a screenshot pass driven from a script has no mouse.
 */
private val probeQuery = System.getProperty("bitchord.probeQuery").orEmpty()
private val probeAutoplay = System.getProperty("bitchord.probeAutoplay") == "true"
private val probeOpenPlayer = System.getProperty("bitchord.probeOpenPlayer") == "true"
private val autoExitMs = System.getProperty("bitchord.autoExitMs")?.toLongOrNull()

/**
 * Where the content router should land on launch — `explore`, `library`,
 * `playlists`, `recent`, `search`, or `detail:<browseId>` — so a scripted
 * screenshot pass can open any page without a hand on the mouse. Off by
 * default; the router opens on Home.
 */
private val probeDestination = System.getProperty("bitchord.probeDestination").orEmpty()

/**
 * The appearance the screenshot pass wants — `SYSTEM`, `LIGHT` or `DARK` — as a
 * read-only override of [AppSettings.themeSetting]. Read-only because a probe
 * must not leave a mark on the user's settings file; it answers to the same
 * [resolveDarkTheme] the switch does, so a forced dark run exercises the real
 * path, backdrop included.
 */
private val probeTheme = System.getProperty("bitchord.theme")
    ?.let { stored -> com.music.bitchord.data.settings.ThemeSetting.entries.firstOrNull { it.name == stored } }

/**
 * The scripted screenshot pair: `shot=<path>` + `shotMs=<delay>` takes one
 * capture of the window's own bounds after the delay — long enough for the
 * page's requests to land — and quits. A screen capture rather than a scene
 * render because the window's shadows and its maximised edges live in the
 * compositor, not in Compose.
 */
private val shotPath = System.getProperty("bitchord.shot").orEmpty()
private val shotMs = System.getProperty("bitchord.shotMs")?.toLongOrNull()

/**
 * Renders the context menu's own panel — the exact composable the right
 * click opens ([BitChordMenuPanel]) — over the page, so the scripted
 * screenshot pass can show the styling without injecting OS clicks: the
 * popup lives in its own native window, which `PrintWindow` cannot see, and
 * a Robot click cannot be aimed reliably across monitors with per-monitor
 * scaling. The preview renders the panel inline, in place — same colours,
 * same rows — while the open/detection machinery itself stays the
 * foundation's, which the row menus in daily use already exercise.
 */
private val menuPreview = System.getProperty("bitchord.menuPreview") == "true"

/**
 * Window geometry and locale, so the acceptance screenshots can be taken at other
 * sizes and in English. When given they win over whatever the last session
 * remembered — a scripted screenshot pass must not depend on how a human last
 * left their window.
 */
private val windowWidth = System.getProperty("bitchord.windowWidth")?.toIntOrNull()
private val windowHeight = System.getProperty("bitchord.windowHeight")?.toIntOrNull()

/**
 * The window's Escape rule: `true` when this event is the one that closes the player, and
 * the caller then clears [ShellState.showPlayer] and swallows it.
 *
 * Three conditions, each of which is a bug if it is dropped:
 *
 *  * **Escape, and nothing else** — every other key goes straight through to the focused
 *    component, exactly as it did before the player existed.
 *  * **released, not pressed** — a held key repeats its press, so consuming the press would
 *    make one long hold close the player and then keep eating keys; the release is the one
 *    event a held Escape produces per hold.
 *  * **only while the player is up** — with it shut the handler answers `false` and Escape
 *    stays the search field's key, which is what the desktop build had before the player.
 *
 * A function of the event's two projections and the flag rather than a literal inside
 * `Window(...)`, so it can be pinned as a table over the three values it reads — see
 * [com.music.bitchord.desktop.EscapeRuleTest]. The projections are taken from the event at the
 * call site rather than inside here because `KeyEvent` has no public constructor on this
 * platform: the rule is the part a test can own, and reading `event.key` / `event.type` is the
 * part Compose does.
 */
internal fun escapeClosesPlayer(key: Key, type: KeyEventType, playerIsOpen: Boolean): Boolean =
    playerIsOpen && key == Key.Escape && type == KeyEventType.KeyUp

/**
 * The window's Alt+arrow rule — this shell's back and forward, the pair of
 * shortcuts every desktop browser and file manager answers.
 *
 * Released, not pressed, for the same reason Escape is: one event per hold.
 * The modifier is a projection passed in alongside the key, so the rule stays
 * a pure function a table can own.
 */
internal fun altArrowNavigates(
    key: Key,
    type: KeyEventType,
    altPressed: Boolean,
): Boolean = altPressed && type == KeyEventType.KeyUp &&
    (key == Key.DirectionLeft || key == Key.DirectionRight)

/** One capture of the window's own bounds — the scripted screenshot pass. */
private fun saveWindowShot(window: java.awt.Window, path: String) {
    // PrintWindow rather than a screen capture: the capture path (Robot /
    // CopyFromScreen) reads the composited desktop and goes black wherever the
    // session is not allowed to read it; PrintWindow asks the *window* to
    // render itself into a memory DC, which needs no screen access at all.
    // PW_RENDERFULLCONTENT (2) is the flag that reaches DirectX-rendered
    // clients — without it a Skia window prints as an empty frame.
    val hwnd = com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Native.getComponentPointer(window))
    val user32 = com.sun.jna.platform.win32.User32.INSTANCE
    val gdi32 = com.sun.jna.platform.win32.GDI32.INSTANCE

    val rect = com.sun.jna.platform.win32.WinDef.RECT()
    user32.GetWindowRect(hwnd, rect)
    val width = rect.right - rect.left
    val height = rect.bottom - rect.top
    require(width > 0 && height > 0) { "window has no bounds: $rect" }

    val hdcWindow = user32.GetDC(hwnd)
    val hdcMemory = gdi32.CreateCompatibleDC(hdcWindow)
    val bitmap = gdi32.CreateCompatibleBitmap(hdcWindow, width, height)
    val previous = gdi32.SelectObject(hdcMemory, bitmap)
    try {
        val printed = user32.PrintWindow(hwnd, hdcMemory, PW_RENDERFULLCONTENT)
        check(printed) { "PrintWindow refused" }
        // Deselect before reading: GetDIBits fails on a bitmap that is
        // currently selected into the DC being read from.
        gdi32.SelectObject(hdcMemory, previous)

        // Top-down 32bpp: the four bytes of each pixel are B, G, R, A, which
        // read back little-endian as exactly Java's 0xAARRGGBB.
        val info = com.sun.jna.platform.win32.WinGDI.BITMAPINFO()
        info.bmiHeader.biWidth = width
        info.bmiHeader.biHeight = -height
        info.bmiHeader.biPlanes = 1
        info.bmiHeader.biBitCount = 32
        info.bmiHeader.biCompression = com.sun.jna.platform.win32.WinGDI.BI_RGB
        val pixels = com.sun.jna.Memory(width.toLong() * height.toLong() * 4)
        val lines = gdi32.GetDIBits(
            hdcMemory, bitmap, 0, height, pixels, info, com.sun.jna.platform.win32.WinGDI.DIB_RGB_COLORS,
        )
        check(lines == height) { "GetDIBits returned $lines of $height lines" }

        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, width, height, pixels.getIntArray(0, width * height), 0, width)
        javax.imageio.ImageIO.write(image, "png", java.io.File(path))
    } finally {
        gdi32.SelectObject(hdcMemory, previous)
        gdi32.DeleteObject(bitmap)
        gdi32.DeleteDC(hdcMemory)
        user32.ReleaseDC(hwnd, hdcWindow)
    }
}

/** PrintWindow's flag that renders the window's live (DWM) content. */
private const val PW_RENDERFULLCONTENT = 2

/** The icon sizes the shell draws at, smallest slot to largest. */
private val ICON_SIZES = intArrayOf(16, 24, 32, 48, 64, 128, 256)

// ---- the Windows backdrop ------------------------------------------------------------
//
// DWM attributes by number — the documented ones, from dwmapi's own header.
private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
private const val DWMWCP_ROUND = 2
private const val DWMWA_SYSTEMBACKDROP_TYPE = 38
private const val DWMSBT_MAINWINDOW = 2

/**
 * The app's dark-mode declaration — `20` since Windows 20H1, `19` on the
 * builds before it that already honoured the value under the old number.
 */
private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY = 19

/** The two dwmapi calls the backdrop needs; loaded late, used defensively. */
private interface DwmApi : com.sun.jna.Library {
    fun DwmSetWindowAttribute(hwnd: com.sun.jna.Pointer, attribute: Int, value: com.sun.jna.Pointer, size: Int): Int
    fun DwmExtendFrameIntoClientArea(hwnd: com.sun.jna.Pointer, margins: com.sun.jna.Pointer): Int
}

/**
 * Mica behind the window, and the platform's rounded corners and shadow over it.
 *
 * Three writes, each useless without the last window being transparent and the
 * chrome being drawn at partial alpha (Shell's business): the frame extended
 * across the whole client area is what lets a DWM backdrop show behind the
 * window's own pixels, the backdrop type is what asks for Mica (a no-op before
 * Windows 11), and the corner preference rounds the frame and brings the
 * standard shadow with it.
 *
 * The fourth write is the dark-mode declaration: Mica renders its own light or
 * dark material following what the app says it is, not what the user's Windows
 * theme is — so a forced-dark BitChord on a light Windows has to say so, or
 * the sliver of backdrop the dark chrome lets through shows the light
 * material. Attribute 20 is the documented number; older builds numbered it
 * 19, so a failed write retries there.
 *
 * Every call is allowed to fail — an older Windows just gets a square,
 * opaque-windowed app — which is why nothing here throws.
 */
private fun applyWindowBackdrop(window: java.awt.Window, isDark: Boolean) {
    runCatching {
        val hwnd = com.sun.jna.Native.getComponentPointer(window) ?: return
        val dwm = com.sun.jna.Native.load("dwmapi", DwmApi::class.java)
        val margins = com.sun.jna.Memory(16)
        for (i in 0 until 4) margins.setInt((i * 4).toLong(), -1)
        dwm.DwmExtendFrameIntoClientArea(hwnd, margins)
        val backdrop = com.sun.jna.Memory(4).apply { setInt(0, DWMSBT_MAINWINDOW) }
        dwm.DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, backdrop, 4)
        val round = com.sun.jna.Memory(4).apply { setInt(0, DWMWCP_ROUND) }
        dwm.DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, round, 4)
        val dark = com.sun.jna.Memory(4).apply { setInt(0, if (isDark) 1 else 0) }
        if (dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, dark, 4) != 0) {
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY, dark, 4)
        }
    }
}

fun main() {
    // Before anything reads a string resource: the resource environment follows
    // the JVM default locale, which is what makes the English pass possible on
    // a Chinese machine. An explicit property — the scripted screenshot pass's
    // override — wins, exactly as the window-geometry probes do; the user's
    // own choice in settings outranks the machine's locale but not the probe.
    val propertyLocale = System.getProperty("bitchord.locale")
    (propertyLocale ?: AppSettings.language.value)
        ?.let { Locale.setDefault(Locale.forLanguageTag(it)) }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Same as the Android service does on create: the object may already hold a value, so
    // assign both true and false rather than only turning it on.
    QueueShuffle.setEnabled(AppSettings.shuffleEnabled.value)
    val player = PlayerController(scope)
    // PlaybackService.kt:5286 restores repeat the same way; the shuffle half of that
    // pair is already applied above, before the player exists. Restored here rather
    // than in the controller's init because a constructor that reads the disk makes
    // unit tests inherit whatever the last one left in settings.properties.
    player.setRepeat(AppSettings.repeatMode.value)
    val home = HomeViewModel(scope)
    val search = SearchViewModel(scope)
    val library = LibraryViewModel(scope)
    val explore = ExploreViewModel(scope)
    val detailPages = DetailPages(scope)

    // Pays InnerTubeX's cold costs (player config, EJS solve, cipher) while the
    // window is still being opened rather than on the first tap.
    InnerTubeXResolver.init()

    // The window's remembered shape, on its own properties file — a second
    // FileStore over settings.properties would clobber whatever AppSettings
    // writes there, since each instance holds the whole file in memory.
    val windowStore = FileStore(AppFiles.file("window.properties"))
    val remembered = WindowGeometry.parseOrNull(windowStore.getString("geometry", ""))

    application {
        // Which tab, whether the player is up, which row's menu is open — out here, above the
        // window, because the key handler below is a parameter of the window and runs before
        // its content: nothing `remember`ed inside that content would be reachable from here.
        // A debug query opens on search, since that is the screen it drives.
        val shellState = remember { ShellState() }
        // The settings dialog is window-level, not a destination — but the
        // scripted screenshot pass opens it the same way it opens pages.
        if (probeDestination == "settings") shellState.showSettings = true
        val nav = remember {
            NavState().apply {
                if (probeQuery.isNotBlank()) open(Destination.Search)
                when {
                    probeDestination == "explore" -> open(Destination.Explore)
                    probeDestination == "library" -> open(Destination.LibrarySongs)
                    probeDestination == "playlists" -> open(Destination.LibraryPlaylists)
                    probeDestination == "recent" -> open(Destination.RecentlyAdded)
                    probeDestination.startsWith("detail:") -> open(
                        Destination.Detail(
                            kind = if (probeDestination.startsWith("detail:MPREb")) {
                                com.music.bitchord.data.model.BrowseType.ALBUM
                            } else {
                                com.music.bitchord.data.model.BrowseType.PLAYLIST
                            },
                            browseId = probeDestination.removePrefix("detail:"),
                            title = "Probe",
                        ),
                    )
                }
            }
        }
        val windowState = rememberWindowState(
            width = (windowWidth ?: remembered?.width ?: 1180).dp,
            height = (windowHeight ?: remembered?.height ?: 780).dp,
            position = WindowPosition.Aligned(Alignment.Center),
            placement = if (remembered?.maximized == true) {
                WindowPlacement.Maximized
            } else {
                WindowPlacement.Floating
            },
        )
        val quit = {
            // The size as it is at close — a maximised window still remembers
            // the bounds it will return to, and those bounds are what
            // `windowState.size` reports, so maximisation rides along as its
            // own flag rather than being folded into the numbers.
            windowStore.putString(
                "geometry",
                WindowGeometry(
                    windowState.size.width.value.toInt(),
                    windowState.size.height.value.toInt(),
                    windowState.placement == WindowPlacement.Maximized,
                ).encode(),
            )
            player.release()
            scope.cancel()
            exitApplication()
        }
        Window(
            onCloseRequest = quit,
            state = windowState,
            // No native frame, and a translucent one: the toolbar is the title
            // bar, the chrome paints over the DWM backdrop at partial alpha,
            // and [applyWindowBackdrop] asks Windows for Mica behind it, with
            // the platform's rounded corners and shadow on top. The window is
            // still resizable (Compose's undecorated resizer takes the edges)
            // and still draggable (the toolbar row is the drag area).
            undecorated = true,
            transparent = true,
            title = "BitChord for Windows",
            // Escape closes the full-screen player; Alt+←/→ walk the navigation
            // stack. Both belong at the window rather than in the pages, where
            // they would only fire while a particular page happened to hold
            // focus. The rules themselves are [escapeClosesPlayer] and
            // [altArrowNavigates].
            onPreviewKeyEvent = { event ->
                when {
                    escapeClosesPlayer(event.key, event.type, shellState.showPlayer) -> {
                        shellState.showPlayer = false
                        true
                    }

                    altArrowNavigates(event.key, event.type, event.isAltPressed) -> {
                        if (event.key == Key.DirectionLeft) nav.goBack() else nav.goForward()
                    }

                    else -> false
                }
            },
        ) {
            // Coil has no network fetcher off Android; artwork is all remote.
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context)
                    .components { add(OkHttpNetworkFetcherFactory()) }
                    .build()
            }
            autoExitMs?.let {
                LaunchedEffect(Unit) {
                    delay(it)
                    quit()
                }
            }
            // The appearance switch, resolved once here: the same answer feeds
            // the colour scheme and the window's Mica backdrop, so the two
            // cannot drift out of step (see [resolveDarkTheme]). The probe's
            // theme, when given, rides in front of the stored one.
            val themeSetting by AppSettings.themeSetting.collectAsState()
            val darkTheme = resolveDarkTheme(probeTheme ?: themeSetting, isSystemInDarkTheme())
            // The window's backdrop, applied once the frame exists to apply it
            // to, and re-declared whenever the appearance switch moves: Mica
            // renders the material the app says it is, so the backdrop has to
            // hear about a forced dark mode the same moment the chrome does.
            LaunchedEffect(darkTheme) {
                applyWindowBackdrop(window, darkTheme)
            }
            LaunchedEffect(Unit) {
                // The mark at every size the shell asks for — 16 for the
                // taskbar, 32 for alt-tab, and up — scaled once, off the one
                // 512 original, so Windows never has to shrink a single huge
                // bitmap for a 16-pixel slot. A lone wordmark here was the bug
                // that drew a sliver of a logo on the taskbar: the icon is the
                // square mark, the wordmark belongs to the toolbar.
                runCatching {
                    val icon = Thread.currentThread().contextClassLoader
                        ?.getResourceAsStream("bitchord_icon.png")
                        ?.use { javax.imageio.ImageIO.read(it) }
                    if (icon != null) {
                        val variants = ICON_SIZES.map { size ->
                            java.awt.image.BufferedImage(
                                size,
                                size,
                                java.awt.image.BufferedImage.TYPE_INT_ARGB,
                            ).also { scaled ->
                                val g = scaled.createGraphics()
                                g.setRenderingHint(
                                    java.awt.RenderingHints.KEY_INTERPOLATION,
                                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                                )
                                g.setRenderingHint(
                                    java.awt.RenderingHints.KEY_RENDERING,
                                    java.awt.RenderingHints.VALUE_RENDER_QUALITY,
                                )
                                g.drawImage(icon, 0, 0, size, size, null)
                                g.dispose()
                            }
                        }
                        window.iconImage =
                            java.awt.image.BaseMultiResolutionImage(*variants.toTypedArray())
                    }
                }
            }
if (shotPath.isNotBlank() && shotMs != null) {
                LaunchedEffect(Unit) {
                    delay(shotMs)
                    runCatching { saveWindowShot(window, shotPath) }
                        .onFailure { System.err.println("shot failed: $it") }
                    quit()
                }
            }

            // The row menu's effects, bound to the player and the navigation
            // stack this scope owns. The menu itself is pure surface — see
            // SongContextMenu.kt — the rows' right-clicks land on these.
            val clipboard = LocalClipboardManager.current
            val songActions = remember(player, nav, clipboard) {
                SongActions(
                    playNext = player::enqueueNext,
                    addToQueue = player::enqueueLast,
                    toggleLike = ::toggleSongLike,
                    startRadio = player::playRadio,
                    openAlbum = { song ->
                        song.albumId?.let { albumId ->
                            nav.open(
                                Destination.Detail(
                                    kind = BrowseType.ALBUM,
                                    browseId = albumId,
                                    title = song.albumName ?: song.title,
                                    subtitle = song.artist,
                                    thumbnailUrl = song.thumbnailUrl,
                                ),
                            )
                        }
                    },
                    openArtist = { song ->
                        song.artistId?.let { artistId ->
                            nav.open(
                                Destination.Detail(
                                    kind = BrowseType.ARTIST,
                                    browseId = artistId,
                                    title = song.artist,
                                    subtitle = null,
                                    thumbnailUrl = song.thumbnailUrl,
                                ),
                            )
                        }
                    },
                    copyLink = { song ->
                        clipboard.setText(
                            AnnotatedString("https://music.youtube.com/watch?v=${song.videoId}"),
                        )
                    },
                    copyLog = { clipboard.setText(AnnotatedString(DebugLog.dump())) },
                    currentVideoId = { player.state.value.song?.videoId },
                )
            }

            BitChordTheme(darkTheme = darkTheme) {
                CompositionLocalProvider(
                    LocalSongActions provides songActions,
                    // The right-click menus draw in the app's own style, not
                    // the platform default's — see BitChordContextMenu.
                    LocalContextMenuRepresentation provides
                        remember { BitChordContextMenuRepresentation() },
                ) {
                    Shell(
                    player = player,
                    home = home,
                    search = search,
                    library = library,
                    explore = explore,
                    detailPages = detailPages,
                    nav = nav,
                    state = shellState,
                    // The window buttons' half of the frame. Read here, in the
                    // composable scope that owns the window state, so a
                    // maximize/restore recomposes the toolbar's glyph.
                    windowMaximized = windowState.placement == WindowPlacement.Maximized,
                    onMinimize = { windowState.isMinimized = true },
                    onToggleMaximize = {
                        windowState.placement =
                            if (windowState.placement == WindowPlacement.Maximized) {
                                WindowPlacement.Floating
                            } else {
                                WindowPlacement.Maximized
                            }
                    },
                    onClose = quit,
                    window = window,
                    initialQuery = probeQuery,
                    autoPlayFirst = probeAutoplay,
                    autoOpenPlayer = probeOpenPlayer,
                )
                // The menu panel's styling probe — see [menuPreview]. Rendered
                // in place rather than through the popup, whose native window
                // the screenshot pipeline cannot see; the colours and rows are
                // the real menu's own.
                if (menuPreview) {
                    val labels = rememberSongActionLabels()
                    val demoItems = listOf(
                        ContextMenuItem(labels.playNext) {},
                        ContextMenuItem(labels.addToQueue) {},
                        ContextMenuItem(labels.like) {},
                        ContextMenuItem(labels.startRadio) {},
                        ContextMenuItem(labels.openAlbum) {},
                        ContextMenuItem(labels.openArtist) {},
                        ContextMenuItem(labels.copyLink) {},
                    )
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        BitChordMenuPanel(
                            items = demoItems,
                            onItem = {},
                            modifier = Modifier.padding(top = 60.dp),
                        )
                    }
                }
                }
            }
        }
    }
}
