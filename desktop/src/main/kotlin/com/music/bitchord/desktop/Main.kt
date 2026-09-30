package com.music.bitchord.desktop

import androidx.compose.runtime.LaunchedEffect
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
 *       -Pbitchord.probeAutoplay=true -Pbitchord.autoExitMs=25000
 */
private val probeQuery = System.getProperty("bitchord.probeQuery").orEmpty()
private val probeAutoplay = System.getProperty("bitchord.probeAutoplay") == "true"
private val autoExitMs = System.getProperty("bitchord.autoExitMs")?.toLongOrNull()

/** Window geometry and locale, so the acceptance screenshots can be taken at other sizes and in English. */
private val windowWidth = System.getProperty("bitchord.windowWidth")?.toIntOrNull() ?: 1180
private val windowHeight = System.getProperty("bitchord.windowHeight")?.toIntOrNull() ?: 780

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
        Window(
            onCloseRequest = quit,
            state = rememberWindowState(width = windowWidth.dp, height = windowHeight.dp),
            title = "BitChord for Windows",
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
                    initialQuery = probeQuery,
                    autoPlayFirst = probeAutoplay,
                )
            }
        }
    }
}
