package com.music.bitchord.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.desktop.playback.PlayerController
import com.music.bitchord.desktop.ui.SearchScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

/**
 * The Android app's palette, minus the dynamic-light variants: this is the
 * dark scheme out of `ui/theme/Theme.kt`, which is what the desktop build
 * paints until artwork-driven theming is ported.
 */
private val DarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0D0D0F),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF2C2C2E),
    error = Color(0xFFFF6B6B),
)

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

fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val player = PlayerController(scope)

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
            state = rememberWindowState(width = 1180.dp, height = 780.dp),
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
            MaterialTheme(colorScheme = DarkColors) {
                SearchScreen(player, initialQuery = probeQuery, autoPlayFirst = probeAutoplay)
            }
        }
    }
}
