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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.Song
import com.music.bitchord.desktop.playback.PlayerController
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.explore
import com.music.bitchord.desktop.resources.home
import com.music.bitchord.desktop.resources.library
import com.music.bitchord.desktop.resources.search
import com.music.bitchord.ui.HomeViewModel
import com.music.bitchord.ui.SearchViewModel
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
 * Which of the shell's surfaces is up, and which row's menu is open.
 *
 * Lives outside every composable because the window's own key handler has to
 * reach it: [androidx.compose.ui.window.Window]'s `onPreviewKeyEvent` is a
 * parameter of the window, evaluated before its content, so it can read and
 * write this but nothing built inside the content lambda could. Esc closing the
 * player is the whole reason — see Main.kt.
 *
 * Observable properties rather than plain fields, so a tab change recomposes the
 * pages and a player toggle recomposes the chrome that draws the player: two
 * independent questions, one object answering them, and only the readers of each
 * hear about it.
 */
class ShellState {
    /** Index into [Shell]'s tab list. */
    var selectedTab by mutableIntStateOf(0)

    /** The full-screen player, over everything. */
    var showPlayer by mutableStateOf(false)

    /** The row whose action menu is up — Task 12 renders it; the player's ⋮ already sets it. */
    var menuSong: Song? = null
}

/**
 * The app's chrome, assembled the way MainActivity assembles it: a frosted top
 * bar, the page below it, and the floating bottom bar with the mini player
 * stacked above it — both capped at the width the Android app caps them at, and
 * centred in whatever window they find themselves in.
 *
 * The glass branch of those bars is compiled but never taken (see
 * ui/components/LiquidGlass.kt), so this is the pair of surfaces the Android app
 * itself shows on API < 31.
 *
 * Above all of that, when [ShellState.showPlayer], the full-screen player. On
 * Android that surface is the deck MainActivity reveals from the bottom, and it
 * is dismissed with the back button; neither exists on this platform, so spec
 * decision 4 replaced both with this overlay and with Esc (Main.kt) — the last
 * child of the same Box, and the whole window.
 */
@Composable
fun Shell(
    player: PlayerController,
    home: HomeViewModel,
    search: SearchViewModel,
    state: ShellState,
    initialQuery: String = "",
    autoPlayFirst: Boolean = false,
    autoOpenPlayer: Boolean = false,
) {
    val hazeState = remember { HazeState() }
    // One snapshot for the whole chrome. The three slice-1 flows this used to collect
    // (`current` / `playing` / `loading`) were copies of fields of this, kept only while
    // the screens were being ported; they are gone from the controller now, and reading
    // `state` is cheaper as well as honest — the playhead is not in it, so a tick still
    // recomposes the scrubber alone.
    val snapshot by player.state.collectAsState()
    val status by player.status.collectAsState()
    val song = snapshot.song

    val tabs = listOf(
        BottomTab(stringResource(Res.string.home), BitChordIcons.Home),
        BottomTab(stringResource(Res.string.explore), BitChordIcons.Explore),
        BottomTab(stringResource(Res.string.library), BitChordIcons.Library),
        BottomTab(stringResource(Res.string.search), BitChordIcons.Search),
    )
    val title = tabs[state.selectedTab].label

    // Debug hook — see Main.kt. Opens the player the first time there is a track to
    // open it on, so the full-screen branch can be screenshotted without a hand on
    // the mouse. Keyed on the song rather than run every recomposition: once it has
    // opened, an Escape stays closed, which is the thing worth being able to see.
    LaunchedEffect(autoOpenPlayer, song?.videoId) {
        if (autoOpenPlayer && song != null) state.showPlayer = true
    }

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
                    when (state.selectedTab) {
                        0 -> HomePage(vm = home, player = player, autoPlayFirst = autoPlayFirst)
                        1 -> NotPortedPlaceholder(title = title)
                        2 -> NotPortedPlaceholder(title = title)
                        else -> SearchPage(
                            vm = search,
                            player = player,
                            initialQuery = initialQuery,
                            autoPlayFirst = autoPlayFirst,
                        )
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
            // Opening a collection takes a request round-trip before there is a
            // track to put a mini player around, so the page says so rather than
            // sitting silent for a second after the tap.
            if (song == null && status != null) {
                Text(
                    status!!,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            song?.let { current ->
                MiniPlayer(
                    song = current,
                    isPlaying = snapshot.isPlaying,
                    isLoading = snapshot.isLoading,
                    hazeState = hazeState,
                    onPlayPause = player::togglePlayPause,
                    // Both halves of the transport, and the 72dp swipe the bar already
                    // carries (`MiniPlayer.kt:123-159`), which was wired to nothing until
                    // the player existed to move the queue.
                    onNext = player::next,
                    onPrevious = player::previous,
                    // The full-screen player this slice built. A tap on the bar's body
                    // opens it; Esc closes it (Main.kt), which is the desktop equivalent
                    // of the sheet's own drag-down.
                    onExpand = { state.showPlayer = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            FloatingBottomBar(
                tabs = tabs,
                selectedIndex = state.selectedTab,
                onTabSelected = { state.selectedTab = it },
                hazeState = hazeState,
            )
        }

        // The player paints last, over the fades and the bottom surfaces. It is
        // deliberately not registered as a haze source: it brings its own gradient
        // backdrop, and a second frost over the content column is how you end up
        // seeing a ghost of the tab bar through the sleeve. The one `hazeSource`
        // above stays the only one — the MiniPlayer and the tab bar under it are
        // covered, not blurred into the player.
        if (state.showPlayer) {
            PlayerPage(
                player = player,
                state = state,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
