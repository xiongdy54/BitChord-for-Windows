package com.music.bitchord.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.music.bitchord.desktop.playback.PlayerController
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.explore
import com.music.bitchord.desktop.resources.home
import com.music.bitchord.desktop.resources.library
import com.music.bitchord.desktop.resources.search
import com.music.bitchord.ui.HomeViewModel
import com.music.bitchord.ui.components.BottomFadeScrim
import com.music.bitchord.ui.components.BottomTab
import com.music.bitchord.ui.components.FLOATING_BAR_MAX_WIDTH
import com.music.bitchord.ui.components.FloatingBottomBar
import com.music.bitchord.ui.components.FrostedTopBar
import com.music.bitchord.ui.components.MiniPlayer
import com.music.bitchord.ui.components.TopBarBlur
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.screens.NotPortedPlaceholder
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.jetbrains.compose.resources.stringResource

/**
 * How wide the page column is allowed to get.
 *
 * The Android layout has no such cap because a phone never needs one. A desktop
 * window does: the shelves are fixed-width cards, so letting them run the full
 * width of a maximised window turns six cards into twenty and loses the
 * composition. Past this the margins grow instead of the layout.
 */
const val CONTENT_MAX_WIDTH = 1080

/**
 * The app's chrome, assembled the way MainActivity assembles it: a frosted top
 * bar, the page below it, and the floating bottom bar with the mini player
 * stacked above it — both capped at the width the Android app caps them at, and
 * centred in whatever window they find themselves in.
 *
 * The glass branch of those bars is compiled but never taken (see
 * ui/components/LiquidGlass.kt), so this is the pair of surfaces the Android app
 * itself shows on API < 31.
 */
@Composable
fun Shell(player: PlayerController, home: HomeViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val hazeState = remember { HazeState() }
    val song by player.current.collectAsState()
    val playing by player.playing.collectAsState()
    val loading by player.loading.collectAsState()

    val tabs = listOf(
        BottomTab(stringResource(Res.string.home), BitChordIcons.Home),
        BottomTab(stringResource(Res.string.explore), BitChordIcons.Explore),
        BottomTab(stringResource(Res.string.library), BitChordIcons.Library),
        BottomTab(stringResource(Res.string.search), BitChordIcons.Search),
    )
    val title = tabs[selectedTab].label

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            FrostedTopBar(title = title, scrolled = false)
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    Modifier.widthIn(max = CONTENT_MAX_WIDTH.dp).fillMaxWidth()
                        .hazeSource(hazeState),
                ) {
                    when (selectedTab) {
                        0 -> HomePage(vm = home, player = player)
                        1 -> NotPortedPlaceholder(title = title)
                        2 -> NotPortedPlaceholder(title = title)
                        else -> NotPortedPlaceholder(title = title)
                    }
                }
            }
        }

        // The fades either end, in the order MainActivity paints them: content,
        // then the top scrim — the bottom floor turned upside down, so both
        // edges share one curve — then the bar's own blur, then the bottom
        // scrim, then the bottom surfaces themselves.
        BottomFadeScrim(
            pageColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.align(Alignment.TopCenter).rotate(180f),
        )
        TopBarBlur(hazeState = hazeState, modifier = Modifier.align(Alignment.TopCenter))
        BottomFadeScrim(
            withMiniPlayer = song != null,
            pageColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
                .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            song?.let { current ->
                MiniPlayer(
                    song = current,
                    isPlaying = playing,
                    isLoading = loading,
                    hazeState = hazeState,
                    onPlayPause = player::togglePlayPause,
                    onNext = {},
                    onPrevious = {},
                    // The full-screen player is the next slice; until it lands a
                    // tap here does nothing rather than opening half a screen.
                    onExpand = {},
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            FloatingBottomBar(
                tabs = tabs,
                selectedIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                hazeState = hazeState,
            )
        }
    }
}
