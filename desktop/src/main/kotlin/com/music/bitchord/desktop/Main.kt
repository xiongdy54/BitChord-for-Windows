package com.music.bitchord.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.desktop.playback.PlayerController
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.ui.HomeViewModel
import com.music.bitchord.ui.SearchViewModel
import com.music.bitchord.ui.shell.Shell
import com.music.bitchord.ui.shell.ShellState
import com.music.bitchord.ui.theme.BitChordTheme
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
 * Task 11's landscape/portrait/compact screenshots are taken at all: nothing in this build
 * reaches the player except a click, and a screenshot pass driven from a script has no mouse.
 */
private val probeQuery = System.getProperty("bitchord.probeQuery").orEmpty()
private val probeAutoplay = System.getProperty("bitchord.probeAutoplay") == "true"
private val probeOpenPlayer = System.getProperty("bitchord.probeOpenPlayer") == "true"
private val autoExitMs = System.getProperty("bitchord.autoExitMs")?.toLongOrNull()

/** Window geometry and locale, so the acceptance screenshots can be taken at other sizes and in English. */
private val windowWidth = System.getProperty("bitchord.windowWidth")?.toIntOrNull() ?: 1180
private val windowHeight = System.getProperty("bitchord.windowHeight")?.toIntOrNull() ?: 780

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
 *    stays the search field's key, which is what the desktop build had before this slice.
 *
 * A function of the event's two projections and the flag rather than a literal inside
 * `Window(...)`, so it can be pinned as a table over the three values it reads — see
 * [com.music.bitchord.desktop.EscapeRuleTest]. The projections are taken from the event at the
 * call site rather than inside here because `KeyEvent` has no public constructor on this
 * platform: the rule is the part a test can own, and reading `event.key` / `event.type` is the
 * part Compose does.
 *
 * Pinned twice over. The table covers every row; the live path was run too, by posting
 * `WM_KEYDOWN`/`WM_KEYUP` for `VK_ESCAPE` to the window's own handle — which, unlike bringing
 * the window forward and typing at it (this machine will not do that), needs no foreground.
 * With the player up the overlay went away and the audio carried on playing.
 */
internal fun escapeClosesPlayer(key: Key, type: KeyEventType, playerIsOpen: Boolean): Boolean =
    playerIsOpen && key == Key.Escape && type == KeyEventType.KeyUp

fun main() {
    // Before anything reads a string resource: the resource environment follows
    // the JVM default locale, which is what makes the English pass possible on
    // a Chinese machine.
    System.getProperty("bitchord.locale")
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

    // Pays InnerTubeX's cold costs (player config, EJS solve, cipher) while the
    // window is still being opened rather than on the first tap.
    InnerTubeXResolver.init()

    application {
        val quit = {
            player.release()
            scope.cancel()
            exitApplication()
        }
        // Which tab, whether the player is up, which row's menu is open — out here, above the
        // window, because the key handler below is a parameter of the window and runs before
        // its content: nothing `remember`ed inside that content would be reachable from here.
        // A debug query opens on the search tab, since that is the screen it drives; the rule
        // used to sit in Shell's own local tab state, and this is the one place it can live now.
        val shellState = remember {
            ShellState().apply { if (probeQuery.isNotBlank()) selectedTab = 3 }
        }
        Window(
            onCloseRequest = quit,
            state = rememberWindowState(width = windowWidth.dp, height = windowHeight.dp),
            title = "BitChord for Windows",
            // Escape closes the full-screen player. Android has a back button and this window
            // has none, so Esc is its equivalent (spec decision 4) — and it belongs at the
            // window rather than in the player, where it would only fire while the player
            // happened to hold focus. The rule itself is [escapeClosesPlayer].
            onPreviewKeyEvent = { event ->
                if (escapeClosesPlayer(event.key, event.type, shellState.showPlayer)) {
                    shellState.showPlayer = false
                    true
                } else {
                    false
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
            BitChordTheme {
                Shell(
                    player = player,
                    home = home,
                    search = search,
                    state = shellState,
                    initialQuery = probeQuery,
                    autoPlayFirst = probeAutoplay,
                    autoOpenPlayer = probeOpenPlayer,
                )
            }
        }
    }
}
