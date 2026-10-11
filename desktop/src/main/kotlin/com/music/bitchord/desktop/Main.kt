package com.music.bitchord.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
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
import com.music.bitchord.auth.AccountController
import com.music.bitchord.auth.CefRuntime
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.AppUpdateChecker
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.desktop.playback.LyricsCoordinator
import com.music.bitchord.download.Downloads
import com.music.bitchord.desktop.playback.PlayerController
import com.music.bitchord.desktop.playback.SleepTimer
import com.music.bitchord.data.SystemClock
import com.music.bitchord.desktop.playback.smtc.SmtcButton
import com.music.bitchord.desktop.playback.smtc.SmtcSession
import com.music.bitchord.desktop.playback.smtc.WinRtSmtcBackend
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
import com.music.bitchord.ui.player.formatSleepCountdown
import com.music.bitchord.ui.player.sleepTimerRemaining
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.after_this_song
import com.music.bitchord.desktop.resources.convert_to_video
import com.music.bitchord.desktop.resources.sleep_timer
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.music.bitchord.ui.screens.OutputDevicePanel
import com.music.bitchord.ui.screens.SourceEditorPanel
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.Locale

/*
 * Debug hooks, off by default. They exist so the window can be driven without
 * a hand on the keyboard — the job Probe does for the data layer, one level
 * up — which is also how the desktop build gets smoke-tested:
 *
 *   ./gradlew -p desktop run -Pbitchord.probeQuery="陈奕迅 致明日的舞" \
 *       -Pbitchord.probeAutoplay=true -Pbitchord.probeOpenPlayer=true -Pbitchord.autoExitMs=25000
 *
 * `probeOpenPlayer` opens the full-screen player as soon as there is a track, which is how
 * the player's screenshots are taken at all: nothing in this build reaches the player except
 * a click, and a screenshot pass driven from a script has no mouse.
 */
private val probeQuery = System.getProperty("bitchord.probeQuery").orEmpty()
private val probeAutoplay = System.getProperty("bitchord.probeAutoplay") == "true"
private val probeOpenPlayer = System.getProperty("bitchord.probeOpenPlayer") == "true"
/**
 * Opens the lyric sheet as soon as the player is up — the same job as
 * [probeOpenPlayer], one surface deeper: nothing reaches the sheet except
 * its glyph and its strip, and a scripted pass has no mouse.
 */
private val probeOpenLyrics = System.getProperty("bitchord.probeOpenLyrics") == "true"
/**
 * Enqueues the first track that reaches the player for download — the whole
 * pipeline's smoke test without a hand on the mouse: resolve, fetch, tag,
 * embed, record. The proof is on the disk afterwards, not on the screen.
 */
private val probeDownload = System.getProperty("bitchord.probeDownload") == "true"
private val autoExitMs = System.getProperty("bitchord.autoExitMs")?.toLongOrNull()

/**
 * The audio quality the scripted run flips to once a track is playing — the
 * quality swap's smoke test without a hand on the settings dialog: play
 * something, move the ceiling, and the swap (re-pick, audition, mid-track
 * cut) either lands audibly and in the log, or the whole seam has a hole.
 */
private val probeQualitySwitch = System.getProperty("bitchord.probeQualitySwitch")
    ?.let { stored -> com.music.bitchord.data.settings.AudioQuality.entries.firstOrNull { it.name == stored } }
private val probeSleepTimerMs = System.getProperty("bitchord.probeSleepTimerMs")?.toLongOrNull()
private val probeSleepAfterTrack = System.getProperty("bitchord.probeSleepAfterTrack") == "true"
private val probeArmTimerMs = System.getProperty("bitchord.probeArmTimerMs")?.toLongOrNull()

/**
 * Plays exactly this videoId as soon as the shell is up — the playback smoke
 * test that answers to no search endpoint: the scripted pass names the row,
 * the player resolves and sounds it, whatever YouTube's search page is shaped
 * like today. Used with [probeQualitySwitch] it drives the whole swap seam.
 */
private val probePlayVideoId = System.getProperty("bitchord.probePlayVideoId").orEmpty()

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
 * The window's translucency as a read-only override — `false` shoots the
 * opaque window, `true` the Mica one — so both modes can be screenshotted
 * without leaving a mark on the settings file, for the same reason
 * [probeTheme] is read-only: a probe must not write the user's choices.
 */
private val probeTranslucent = System.getProperty("bitchord.translucent")?.toBooleanStrictOrNull()

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
 * The menu preview renders the *player's* menu variant: the sleep timer row
 * on top. Pair with `bitchord.probeArmTimerMs` to screenshot the row in its
 * countdown state, and with `bitchord.probeSleepAfterTrack` for the
 * after-this-song wording — the same flows the real row reads.
 */
private val probeSleepPreview = System.getProperty("bitchord.probeSleepPreview") == "true"

/**
 * The output picker's panel, rendered inline — see the menu preview's note:
 * a dialog nested in the settings dialog lives in a native window of its own,
 * one layer further out than the capture can reach.
 */
private val probeOutputPreview = System.getProperty("bitchord.probeOutputPreview") == "true"

/**
 * The equalizer's panel, rendered inline for the same reason the output
 * picker's is — the dialog-within-a-dialog's native window sits where the
 * capture cannot see it, so the scripted pass shoots this panel instead,
 * controls and states exactly the real one's.
 */
private val probeEqualizerPreview = System.getProperty("bitchord.probeEqualizerPreview") == "true"
private val probeSourceEditorPreview = System.getProperty("bitchord.probeSourceEditorPreview") == "true"

/**
 * The language hot-swap's scripted proof: `bitchord.probeLocaleSwitch=zh` (or
 * `en`) waits five seconds after launch and moves the language flow — in
 * memory only, never the file the user's choice lives in — so a screenshot
 * pass can capture the same session speaking two languages, which is the
 * whole feature. Nothing restarts: that is the point being proven.
 */
private val probeLocaleSwitch = System.getProperty("bitchord.probeLocaleSwitch").orEmpty()

/**
 * The machine's own locale tag, captured before anything (the property
 * override, the stored setting) had a chance to move the JVM default — "the
 * system" in the language radio's answer, which a switch away must be able to
 * come back to.
 */
private val systemLocaleTag: String = java.util.Locale.getDefault().toLanguageTag()

/** The screenshot pass's locale override — it outranks the stored setting. */
private val probeLocale: String? = System.getProperty("bitchord.locale")


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
    val hwnd = com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Native.getComponentPointer(window))
    saveHwndShot(hwnd, path)
}

/**
 * One capture of any top-level window by handle — the main window's shot and
 * the context-menu probe's popup capture are the same PrintWindow routine over
 * different handles.
 */
private fun saveHwndShot(hwnd: com.sun.jna.platform.win32.WinDef.HWND, path: String) {
    // PrintWindow rather than a screen capture: the capture path (Robot /
    // CopyFromScreen) reads the composited desktop and goes black wherever the
    // session is not allowed to read it; PrintWindow asks the *window* to
    // render itself into a memory DC, which needs no screen access at all.
    // PW_RENDERFULLCONTENT (2) is the flag that reaches DirectX-rendered
    // clients — without it a Skia window prints as an empty frame.
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



/**
 * The screen's work area, in physical pixels — bounds minus the taskbar and any other
 * reserved edge. Null when AWT will not say: a headless or half-ready configuration gets
 * no opinion, and the callers that clamp then simply do not.
 */
private fun workAreaOf(config: java.awt.GraphicsConfiguration): IntSize? = runCatching {
    val bounds = config.bounds
    val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(config)
    IntSize(
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom,
    )
}.getOrNull()

/** The work area of the screen [window] sits on — the honest clamp for that window. */
private fun workAreaOf(window: java.awt.Window): IntSize? =
    window.graphicsConfiguration?.let { workAreaOf(it) }

/**
 * The default screen's work area — the shape a save-time clamp checks against, running
 * as it does above the window's own scope. The window's screen is the more honest
 * answer, but the launch clamp reads exactly that and corrects any drift the moment
 * the next session opens.
 */
private fun defaultWorkArea(): IntSize? = runCatching {
    workAreaOf(java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration)
}.getOrNull()

/** The icon sizes the shell draws at, smallest slot to largest. */
private val ICON_SIZES = intArrayOf(16, 24, 32, 48, 64, 128, 256)

/**
 * How often the playing position is re-anchored into the system's media
 * overlay, in milliseconds. The overlay advances the shown time itself from
 * the last anchor; this only corrects its drift — see [SmtcSession.update].
 */
private const val SMTC_POSITION_PERIOD_MS = 2_000L

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
 * Three writes, each useless without the window being transparent and the
 * chrome being drawn at partial alpha (Shell's business): the frame extended
 * across the whole client area is what lets a DWM backdrop show behind the
 * window's own pixels, the backdrop type is what asks for Mica (a no-op before
 * Windows 11), and the corner preference rounds the frame and brings the
 * standard shadow with it. The first two stay off in the opaque mode the
 * settings switch selects — there is nothing behind an opaque window's pixels
 * for them to reveal — while the corner preference is a frame property and
 * rounds both modes.
 *
 * The last write is the dark-mode declaration: Mica renders its own light or
 * dark material following what the app says it is, not what the user's Windows
 * theme is — so a forced-dark BitChord on a light Windows has to say so, or
 * the sliver of backdrop the dark chrome lets through shows the light
 * material. Attribute 20 is the documented number; older builds numbered it
 * 19, so a failed write retries there.
 *
 * Every call is allowed to fail — an older Windows just gets a square,
 * opaque-windowed app — which is why nothing here throws.
 */
private fun applyWindowBackdrop(window: java.awt.Window, isDark: Boolean, translucent: Boolean) {
    runCatching {
        val hwnd = com.sun.jna.Native.getComponentPointer(window) ?: return
        val dwm = com.sun.jna.Native.load("dwmapi", DwmApi::class.java)
        if (translucent) {
            val margins = com.sun.jna.Memory(16)
            for (i in 0 until 4) margins.setInt((i * 4).toLong(), -1)
            dwm.DwmExtendFrameIntoClientArea(hwnd, margins)
            val backdrop = com.sun.jna.Memory(4).apply { setInt(0, DWMSBT_MAINWINDOW) }
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, backdrop, 4)
        }
        val round = com.sun.jna.Memory(4).apply { setInt(0, DWMWCP_ROUND) }
        dwm.DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, round, 4)
        val dark = com.sun.jna.Memory(4).apply { setInt(0, if (isDark) 1 else 0) }
        if (dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, dark, 4) != 0) {
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY, dark, 4)
        }
    }
}

fun main() {
    // Uncaught anything, kept. A thread that dies during the quit path — the
    // EDT under a System.exit already in flight — can be cut off mid-print,
    // which is how an exception once reached the log as a bare
    // "Exception in thread" with neither name nor stack: stderr's write raced
    // the halt and lost. The synchronous file write wins that race; DebugLog
    // still gets the line (its ring is the in-app view), the file is the
    // after-the-fact one, last crash only.
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            AppFiles.file("crash-latest.txt").writeText(
                buildString {
                    appendLine("thread: ${thread.name}")
                    appendLine("at: ${java.time.LocalDateTime.now()}")
                    appendLine(throwable.stackTraceToString())
                },
                java.nio.charset.StandardCharsets.UTF_8,
            )
        }
        DebugLog.e("BitChord", "uncaught on ${thread.name}", throwable)
    }

    // Before anything reads a string resource: the resource environment follows
    // the JVM default locale, which is what makes the English pass possible on
    // a Chinese machine. An explicit property — the scripted screenshot pass's
    // override — wins, exactly as the window-geometry probes do; the user's
    // own choice in settings outranks the machine's locale but not the probe.
    (probeLocale ?: AppSettings.language.value)
        ?.let { Locale.setDefault(Locale.forLanguageTag(it)) }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // The screenshot pass's local-library seed: the scan's folder list for the
    // session, comma-separated, seeded into the flow's in-memory value only —
    // never the file the user's own choices live in (the theme probe's same
    // discipline, one setting over). A scripted pass has no file picker.
    System.getProperty("bitchord.localFolders")?.takeIf { it.isNotBlank() }?.let { seeded ->
        AppSettings.localMusicFolders.value = seeded.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    // Same as the Android service does on create: the object may already hold a value, so
    // assign both true and false rather than only turning it on.
    QueueShuffle.setEnabled(AppSettings.shuffleEnabled.value)
    // Upstream BitChordApplication.onCreate: seed the built-in kinds and load
    // the stored list before anything can ask for a source.
    com.music.bitchord.data.sources.SourceRegistry.init()
    // The glass probe's read-only override of AppSettings.liquidGlass. In-memory
    // only, never the file the user's own choice lives in — the theme probe's
    // same discipline, one setting over.
    System.getProperty("bitchord.liquidGlass")?.let { seeded ->
        AppSettings.liquidGlass.value = seeded == "true"
    }
    // The volume-bar probe's read-only override, same discipline: a screenshot
    // pass shows the player with and without its volume row and must not leave
    // the choice behind on the user's settings file.
    System.getProperty("bitchord.hideVolumeBar")?.let { seeded ->
        AppSettings.hideVolumeBar.value = seeded == "true"
    }
    val player = PlayerController(scope)
    val lyrics = LyricsCoordinator(scope)
    // The quality swap's scripted half: wait for a track to actually sound,
    // give its first measurement a beat to land, then move the ceiling. What
    // follows in the log is the whole seam — re-pick, audition, swap — and on
    // screen the badge's "switching quality" beat. Driven off the player's
    // state flow rather than composition, so the probe does not depend on
    // which frames the window happens to compose.
    probeQualitySwitch?.let { target ->
        // The probe has to MOVE the ceiling; a settings file that already
        // holds the target (a previous probe run wrote it) would make the
        // switch a same-value no-op, so every run starts from Lossless.
        AppSettings.setAudioQuality(com.music.bitchord.data.settings.AudioQuality.LOSSLESS)
        scope.launch {
            player.state.first { it.song != null && it.isPlaying }
            delay(3_000)
            player.setAudioQuality(target)
        }
    }
    // The sleep timer's two probes, both needing a track to actually sound
    // first (pair with bitchord.probePlayVideoId like the quality probe):
    //   probeSleepTimerMs=<n>  — n ms into the track, arm a deadline 3s out;
    //                          the watcher's pause is the log line that follows.
    //   probeSleepAfterTrack   — arm "after this song"; the *absence* of a
    //                          following resolve when the track ends is the
    //                          proof, beside the holding-at-the-row log line.
    probeSleepTimerMs?.let { waitMs ->
        scope.launch {
            player.state.first { it.song != null && it.isPlaying }
            delay(waitMs)
            SleepTimer.deadline.value = SystemClock.elapsedRealtime() + 3_000
            DebugLog.d("BitChord", "probe: sleep timer armed for 3s out")
        }
    }
    if (probeSleepAfterTrack) {
        scope.launch {
            player.state.first { it.song != null && it.isPlaying }
            SleepTimer.startAfterTrack()
            DebugLog.d("BitChord", "probe: sleep timer armed for after this song")
        }
    }
    // The countdown screenshots' arm, through the same entry the picker's
    // rows use — a deadline set behind the flow's back would tick without
    // ticking its preset's checkmark.
    probeArmTimerMs?.let { ms ->
        SleepTimer.start(((ms + 59_999) / 60_000).toInt().coerceAtLeast(1))
    }
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

    // The session: the stored Google login is restored before the window is
    // up, and every accepted sign-in, profile switch and sign-out reloads the
    // listener's pages. The reload list is the shell's knowledge — which view
    // models exist, which pages they own — which is why it is wired here and
    // not inside the controller. Cookie before channel: the cookie setter
    // clears any channel choice, so the controller applies them in that order.
    val accounts = AccountController(scope)
    accounts.onSessionChanged = {
        Innertube.ensureSessionScope()
        home.refresh()
        library.retrySongs()
        library.retryPlaylists()
        library.retryRecents()
    }
    // The home page's lead shelf is the listener's own account fact — the
    // "recently played" the shell could never ask for signed out. The hook is
    // a lambda so the view model never touches the controller.
    home.signedIn = { accounts.signedIn.value }
    accounts.restoreActiveSession()

    // Pays InnerTubeX's cold costs (player config, EJS solve, cipher) while the
    // window is still being opened rather than on the first tap.
    InnerTubeXResolver.init()

    // The update check, once per launch like upstream's — fire and forget,
    // silent on every failure (no release yet reads as nothing to say).
    scope.launch { AppUpdateChecker.check() }

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
        // The output picker lives inside the settings dialog; the screenshot
        // pass cannot click, so the dialog gets told to open its own child on
        // start (see probeDestination == "outputdevices").
        var openAudioOutputOnStart by remember { mutableStateOf(false) }
        var openEqualizerOnStart by remember { mutableStateOf(false) }
        var openSourceEditorOnStart by remember { mutableStateOf(false) }
        var scrollSettingsToEndOnStart by remember { mutableStateOf(false) }
        if (probeDownload) {
            var downloadProbeFired by remember { mutableStateOf(false) }
            val snapshot by player.state.collectAsState()
            LaunchedEffect(snapshot.song?.videoId, snapshot.durationMs) {
                val current = snapshot.song
                if (current != null && snapshot.durationMs > 0 && !downloadProbeFired) {
                    downloadProbeFired = true
                    Downloads.enqueue(current)
                }
            }
        }
        if (probePlayVideoId.isNotBlank()) {
            var playProbeFired by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (playProbeFired) return@LaunchedEffect
                playProbeFired = true
                player.playOneOff(
                    Song(probePlayVideoId, "Probe track", "Probe artist", null),
                    source = PlaybackSourceType.QUEUE,
                    sourceTitle = "Probe",
                    sourceId = null,
                )
            }
        }
        // The settings dialog is window-level, not a destination — but the
        // scripted screenshot pass opens it the same way it opens pages. The
        // sleep timer and the output picker are window-level the same way.
        if (probeDestination == "settings") shellState.showSettings = true
        if (probeDestination == "sleeptimer") shellState.showSleepTimer = true
        if (probeDestination == "signin") shellState.showLogin = true
        if (probeDestination == "downloadsmanager") shellState.showDownloadsManager = true
        // The hot-swap proof's arm — see [probeLocaleSwitch]. The flow moves
        // in memory only; the file the user's own choice lives in is untouched.
        if (probeLocaleSwitch.isNotBlank()) {
            LaunchedEffect(Unit) {
                delay(5_000)
                AppSettings.language.value = probeLocaleSwitch
                DebugLog.d("BitChord", "probe: language -> $probeLocaleSwitch (hot)")
            }
        }
        if (probeDestination == "outputdevices") {
            shellState.showSettings = true
            openAudioOutputOnStart = true
        }
        if (probeDestination == "sources") {
            shellState.showSettings = true
            scrollSettingsToEndOnStart = true
        }
        if (probeDestination == "sourceeditor") {
            shellState.showSettings = true
            openSourceEditorOnStart = true
        }
        if (probeDestination == "equalizer") {
            shellState.showSettings = true
            openEqualizerOnStart = true
        }
        val nav = remember {
            NavState().apply {
                if (probeQuery.isNotBlank()) open(Destination.Search)
                when {
                    probeDestination == "explore" -> open(Destination.Explore)
                    probeDestination == "library" -> open(Destination.LibrarySongs)
                    probeDestination == "playlists" -> open(Destination.LibraryPlaylists)
                    probeDestination == "downloads" -> open(Destination.Downloads)
                    probeDestination == "localmusic" -> open(Destination.LocalMusic)
                    probeDestination == "recent" -> open(Destination.RecentlyAdded)
                    probeDestination == "history" -> open(Destination.History)
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
        // The window's translucency, pinned for the session. The platform cannot
        // change a window's transparency once the window is displayable, so the
        // setting is next-launch by design — the settings row says so — and a
        // value that moved under a live `Window(transparent = …)` would ask for
        // exactly the thing it refuses to do. The probe's override rides in front.
        val translucentWindow = remember { probeTranslucent ?: AppSettings.translucentWindow.value }
        // What the frame measured the last time it was a floating window. On this
        // platform `windowState.size` tracks whatever pixels the window currently
        // is — restore bounds included — so a session closed while maximised or
        // fullscreen would otherwise record the *screen's* shape as the next
        // session's floating one, and the restored frame would open covering the
        // screen with its title bar above it. Only floating sizes pass this gate.
        var floatingSize by remember { mutableStateOf(windowState.size) }
        LaunchedEffect(Unit) {
            snapshotFlow { windowState.placement to windowState.size }
                .collect { (placement, size) ->
                    if (placement == WindowPlacement.Floating) floatingSize = size
                }
        }
            // The system's media controls — the overlay, the media keys, the
        // taskbar's thumbnail surface. Built against the real Windows backend;
        // a machine where the WinRT activation fails degrades to no system
        // surface and nothing else. Commands arrive on the backend's own
        // threads and hop onto the main dispatcher before touching the player,
        // the same thread every other transport caller uses.
        val smtc = remember { SmtcSession(WinRtSmtcBackend()) }
        val quit = {
                // The last floating size, not today's: a maximised or fullscreen close
                // would otherwise save the whole screen as the shape to come back to.
                // Maximisation rides along as its own flag; a fullscreen close reads as
                // simply not maximised, because the screen's bounds are not a shape
                // worth restoring to. The clamp is the last gate — a geometry file
                // written here must be one the next launch can open into.
                val work = defaultWorkArea()
                val geometry = WindowGeometry(
                    floatingSize.width.value.toInt(),
                    floatingSize.height.value.toInt(),
                    windowState.placement == WindowPlacement.Maximized,
                ).let { shape ->
                    if (work != null) shape.clampedInto(work.width, work.height) else shape
                }
                windowStore.putString("geometry", geometry.encode())
                smtc.release()
                player.release()
                // The login browser's engine. Open dialogs' browsers close
                // first — the app's own native shutdown faults under a live
                // browser — then the engine itself; whatever thread survives
                // anyway is reaped by the JVM's halt a line below.
                CefRuntime.shutdown()
                scope.cancel()
                // The scenes go before the windows do. ComposeWindow.dispose
                // runs the AWT teardown first and disposes the compose scene
                // second — and the scene's goodbye layout, reached through
                // removeNotify → onComponentDetached → releaseFocus, measures
                // a component that is already detached: a getLocationOnScreen
                // on a hidden peer that throws, and the pair escaped every
                // uncaught path as bare "Exception in thread" headers (the
                // exit probe's noise, stack cut by the halt). Disposing each
                // window's panel first — the very call ComposeWindow.dispose
                // itself makes second — nulls the container, and removeNotify
                // then finds nothing to measure. Windows that are not compose
                // windows have no such field and simply skip the step.
                for (awtWindow in java.awt.Window.getWindows()) {
                    runCatching {
                        val field = awtWindow.javaClass.getDeclaredField("composePanel")
                        field.isAccessible = true
                        field.get(awtWindow)?.let { panel ->
                            // The panel class is internal to compose; the
                            // dispose call goes through reflection for the
                            // same reason the field does.
                            val dispose = panel.javaClass.getMethod("dispose")
                            dispose.isAccessible = true
                            dispose.invoke(panel)
                        }
                    }
                    runCatching { awtWindow.dispose() }
                }
                // Exit here, on this dispatch. The scene teardown above leaves
                // compose's FlushCoroutineDispatcher one last task in flight —
                // the effect job's unwind signal, thrown from its completion
                // hook into a runner that is already gone — and it only fires
                // if the event pump gets another cycle. Exiting on this
                // dispatch means it never does. One bare "Exception in
                // thread" line can still survive a quit: the hidden PoToken
                // browser's offscreen GL layer (JOGL under JCEF) reports a
                // callback exception from its own native side, outside every
                // Java hook — cosmetic, and a question for the PoToken
                // substrate, not for this path.
                kotlin.system.exitProcess(0)
            }
        Window(
            onCloseRequest = quit,
            state = windowState,
            // No native frame; translucent when the settings switch says so. In
            // the translucent window the toolbar is the title bar, the chrome
            // paints over the DWM backdrop at partial alpha, and
            // [applyWindowBackdrop] asks Windows for Mica behind it, with the
            // platform's rounded corners and shadow on top. With the switch off
            // the chrome paints solid on an opaque frame — no Mica, and the
            // cheaper per-pixel path that makes resizing smoother. Either way
            // the window is still resizable (Compose's undecorated resizer takes
            // the edges) and still draggable (the toolbar row is the drag area).
            undecorated = true,
            transparent = translucentWindow,
            title = "BitChord for Windows",
            // Escape closes the full-screen player; Alt+←/→ walk the navigation
            // stack. Both belong at the window rather than in the pages, where
            // they would only fire while a particular page happened to hold
            // focus. The rules themselves are [escapeClosesPlayer] and
            // [altArrowNavigates].
            onPreviewKeyEvent = { event ->
                when {
                    escapeClosesPlayer(event.key, event.type, shellState.showPlayer) -> {
                        // The lyric sheet is the player's own first step back:
                        // Esc closes it, then the player, never both at once.
                        if (shellState.lyricsOpen) shellState.lyricsOpen = false
                        else shellState.showPlayer = false
                        true
                    }

                    altArrowNavigates(event.key, event.type, event.isAltPressed) -> {
                        if (event.key == Key.DirectionLeft) nav.goBack() else nav.goForward()
                    }

                    else -> false
                }
            },
        ) {
            // The size on disk, or the default above, can still overshoot the screen
            // the window opens on — a display swapped or re-scaled since the last
            // session, or a shape an undecorated frame allowed a drag to build past
            // the screen's edge. Every arrival in Floating re-clamps against that
            // screen's work area and re-centres: the first composition, and each
            // restore out of maximised or fullscreen, whose bounds the platform
            // reports as the screen's own. Pixel units, because `WindowState` on
            // this platform carries the window's pixel measure in its `Dp` fields —
            // the same values the geometry file stores.
            LaunchedEffect(windowState.placement) {
                if (windowState.placement != WindowPlacement.Floating) return@LaunchedEffect
                val work = workAreaOf(window) ?: return@LaunchedEffect
                val width = windowState.size.width.value.toInt()
                val height = windowState.size.height.value.toInt()
                if (width > work.width || height > work.height) {
                    windowState.size = DpSize(
                        width.coerceAtMost(work.width).dp,
                        height.coerceAtMost(work.height).dp,
                    )
                    windowState.position = WindowPosition.Aligned(Alignment.Center)
                }
            }
            // Coil has no network fetcher off Android; artwork is all remote —
            // plus the local files' embedded covers, which RemoteArtworkStore
            // serves as file URIs the platform fetcher handles natively. The
            // WebDAV interceptor rides along so cover loads aimed at the
            // configured server carry its credential the way playback does
            // (the Android app registers it the same way, in BitChordApplication).
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context)
                    .components { add(com.music.bitchord.data.webdav.WebDavCoilAuth()) }
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
            // The translucency rides along because an opaque window has no
            // backdrop behind it to ask for.
            LaunchedEffect(darkTheme) {
                applyWindowBackdrop(window, darkTheme, translucentWindow)
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
            // The system media surface. The window's own HWND — the same way
            // [applyWindowBackdrop] reaches the DWM — claims the SMTC slot;
            // from then on the overlay and the media keys ride the player's
            // state. `attach` answers once the backend is built, so the first
            // state push below lands on a live overlay rather than vanishing.
            // The state collector reads the position *outside* the tracked
            // read on purpose: the playhead moves twice a second and the
            // overlay is self-paced from the last anchor — full pushes run on
            // track/state changes, and the ticker at the bottom re-anchors
            // against drift.
            LaunchedEffect(smtc) {
                runCatching {
                    val hwndPointer = com.sun.jna.Native.getComponentPointer(window)
                    smtc.attach(com.sun.jna.Pointer.nativeValue(hwndPointer))
                }
                smtc.onButton = { button ->
                    scope.launch(Dispatchers.Main) {
                        when (button) {
                            SmtcButton.PLAY -> if (!player.state.value.isPlaying) player.togglePlayPause()
                            SmtcButton.PAUSE -> if (player.state.value.isPlaying) player.togglePlayPause()
                            // No stop action exists in the shell's own surface.
                            SmtcButton.STOP -> {}
                            SmtcButton.NEXT -> player.next()
                            SmtcButton.PREVIOUS -> player.previous()
                        }
                    }
                }
                smtc.onSeekRequested = { ms ->
                    scope.launch(Dispatchers.Main) { player.seekTo(ms) }
                }
                snapshotFlow {
                    val state = player.state.value
                    Triple(state.song, state.isPlaying, state.durationMs)
                }.collect { (song, playing, duration) ->
                    smtc.update(song, playing, player.state.value.position.positionMs, duration)
                }
            }
            LaunchedEffect(smtc) {
                while (isActive) {
                    delay(SMTC_POSITION_PERIOD_MS)
                    val state = player.state.value
                    smtc.update(state.song, state.isPlaying, state.position.positionMs, state.durationMs)
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
                    download = { song -> Downloads.enqueue(song) },
                    cancelDownload = { song -> Downloads.cancel(song.videoId) },
                    deleteDownload = { song ->
                        scope.launch { Downloads.delete(song.videoId) }
                    },
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

            // The language, hot (slice 19). The strings resolve from the JVM
            // default locale, re-read by every `stringResource` call — what a
            // change needs is (a) the locale set and (b) a rebuilt subtree,
            // in that order. `remember(localeTag)` does the first exactly
            // where it sits: inside composition, *before* the keyed children
            // below compose and read the locale — an effect here would run
            // after them and the rebuild would read the old language (the
            // first attempt's bug, visible in its screenshot). `key(localeTag)`
            // is the rebuild: a different key retires the whole shell's
            // composition and composes it fresh. Everything that *holds* a
            // session — shell state, navigation, view models, player — was
            // built before this point and survives. The probe's override
            // rides in front; the machine's locale, captured at load, is what
            // "System" comes back to. Setting a global inside composition is
            // deliberate impurity, paid for by the ordering it buys.
            val languageSetting by AppSettings.language.collectAsState()
            val localeTag = probeLocale ?: (languageSetting ?: systemLocaleTag)
            remember(localeTag) {
                Locale.setDefault(Locale.forLanguageTag(localeTag))
                true
            }
            BitChordTheme(darkTheme = darkTheme) {
                key(localeTag) {
                CompositionLocalProvider(
                    LocalSongActions provides songActions,
                    // The glass relay (slice 17): upstream MainActivity's
                    // LocalLiquidGlassEnabled, fed from the same setting.
                    com.music.bitchord.ui.glass.LocalLiquidGlassEnabled provides
                        AppSettings.liquidGlass.value,
                    // The right-click menus draw in the app's own style, not
                    // the platform default's — see BitChordContextMenu.
                    LocalContextMenuRepresentation provides
                        remember { BitChordContextMenuRepresentation() },
                ) {
                    Shell(
                    player = player,
                    lyrics = lyrics,
                    autoOpenLyrics = probeOpenLyrics,
                    home = home,
                    search = search,
                    library = library,
                    explore = explore,
                    detailPages = detailPages,
                    nav = nav,
                    state = shellState,
                    accounts = accounts,
                    onOpenLogin = { mode ->
                        shellState.loginMode = mode
                        shellState.showLogin = true
                    },
                    // The chrome's own half of the same switch — whether the
                    // toolbar and the sidebar paint at partial alpha or solid.
                    translucentWindow = translucentWindow,
                    // The window buttons' half of the frame. Read here, in the
                    // composable scope that owns the window state, so a
                    // maximize/restore recomposes the toolbar's glyph. Fullscreen
                    // counts as maximised for the glyph and the drag-restore: a
                    // frame the shell took over the whole screen for should still
                    // answer its title bar the way a maximised one does.
                    windowMaximized = windowState.placement != WindowPlacement.Floating,
                    onMinimize = { windowState.isMinimized = true },
                    onToggleMaximize = {
                        // Floating goes to maximised; either overlaid placement —
                        // maximised or the shell's fullscreen — comes back to
                        // floating, which is what every title bar's middle button
                        // and every drag off a maximised frame means.
                        windowState.placement =
                            if (windowState.placement == WindowPlacement.Floating) {
                                WindowPlacement.Maximized
                            } else {
                                WindowPlacement.Floating
                            }
                    },
                    onClose = quit,
                    window = window,
                    initialQuery = probeQuery,
                    autoPlayFirst = probeAutoplay,
                    autoOpenPlayer = probeOpenPlayer,
                    // The output picker's scripted open — see probeDestination.
                    autoOpenAudioOutput = openAudioOutputOnStart,
                    autoOpenEqualizer = openEqualizerOnStart,
                    autoOpenSourceEditor = openSourceEditorOnStart,
                    scrollSettingsToEnd = scrollSettingsToEndOnStart,
                )
                // The menu panel's styling probe — see [menuPreview]. Rendered
                // in place rather than through the popup, whose native window
                // the screenshot pipeline cannot see; the colours and rows are
                // the real menu's own. With a track playing and the sleep
                // probe armed, the rows are the *player's* menu: the timer row
                // on top, the song's own rows under it.
                if (menuPreview) {
                    val labels = rememberSongActionLabels()
                    // The player-menu variant: the sleep timer row on top of
                    // the current song's rows, exactly what a right-click on
                    // the full-screen player offers. The armed label reads the
                    // same flows the real menu does, one second stale at most.
                    val sleepRow = if (probeSleepPreview) {
                        val remaining = sleepTimerRemaining()
                        val afterTrack by SleepTimer.afterTrack.collectAsState()
                        val base = stringResource(Res.string.sleep_timer)
                        when {
                            remaining != null -> "$base · ${formatSleepCountdown(remaining)}"
                            afterTrack -> "$base · ${stringResource(Res.string.after_this_song)}"
                            else -> base
                        }.let { label -> listOf(ContextMenuItem(label) {}) }
                    } else {
                        emptyList()
                    }
                    val demoItems = sleepRow + listOf(
                        // The version row (slice 21), as the player's own menu
                        // words it for a non-video row — the probe's track is
                        // a plain song, so the video is what it offers.
                        ContextMenuItem(stringResource(Res.string.convert_to_video)) {},
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
                // The output picker's styling probe — see [probeOutputPreview].
                // A dialog inside the settings dialog is a window inside a
                // window, and the capture sees neither; the panel renders here
                // in its own surface, rows and enumeration the real one's.
                if (probeSourceEditorPreview) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SourceEditorPanel(existing = null, onDismiss = {})
                    }
                }
                if (probeOutputPreview) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 6.dp,
                            modifier = Modifier.widthIn(max = 420.dp),
                        ) {
                            OutputDevicePanel(
                                enumerateDevices = { player.audioOutputDevices() },
                                onSelect = { device ->
                                    player.setAudioOutputDevice(device?.id, device?.name)
                                },
                            )
                        }
                    }
                }
                // The equalizer's styling probe — see [probeEqualizerPreview]:
                // the same inline-panel trick as the output picker above, and
                // the settings dialog itself writes the flows it reads, so the
                // three states (dynamic pad / manual faders / preset list)
                // shoot from the same panel with the settings file deciding.
                if (probeEqualizerPreview) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 6.dp,
                            modifier = Modifier.widthIn(max = 460.dp),
                        ) {
                            com.music.bitchord.ui.screens.EqualizerPanel()
                        }
                    }
                }
                }
                }

            }
        }
    }
}
