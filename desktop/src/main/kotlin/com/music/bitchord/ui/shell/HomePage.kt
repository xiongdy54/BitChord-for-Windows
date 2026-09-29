package com.music.bitchord.ui.shell

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.desktop.playback.PlayerController
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.listen_now
import com.music.bitchord.ui.HomeViewModel
import com.music.bitchord.ui.components.topBarContentPadding
import com.music.bitchord.ui.screens.HomeScreen
import org.jetbrains.compose.resources.stringResource

/**
 * The home feed, wired the way MainActivity wires it: the same title, the same
 * content insets — which have to clear the top bar and whichever of the two
 * bottom surfaces is up — and the same derivation of a card's artist from its
 * subtitle.
 *
 * Tapping a track card plays it. Upstream starts a radio seeded by the shelf the
 * card came from; that is the queue's job and arrives with it. Tapping a card
 * that points at a collection opens its page — the detail screen, a later slice —
 * and does nothing until then.
 */
@Composable
fun HomePage(vm: HomeViewModel, player: PlayerController, autoPlayFirst: Boolean = false) {
    val state by vm.home.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val recentlyPlayedLoading by vm.recentlyPlayedLoading.collectAsState()
    val song by player.current.collectAsState()
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(Unit) { vm.loadHome() }

    /**
     * What a card tap does.
     *
     * A track card plays its track. A collection card — and on the signed-out
     * feed that is every card: the shelves come back as playlists, each with a
     * browseId and no videoId — asks the collection what it holds and plays the
     * first of it. Upstream opens the collection's own page on that tap and
     * plays from there; this is that without the page, which a later slice
     * brings.
     */
    fun openCard(item: ShelfItem) {
        when {
            item.videoId != null -> player.play(
                Song(
                    videoId = item.videoId,
                    title = item.title,
                    artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                    thumbnailUrl = item.thumbnailUrl,
                ),
            )

            item.browseId != null -> player.playCollection(item.browseId, item.title)
        }
    }

    // Debug hook — see Main.kt: the same path a tap takes, so the screenshot
    // test exercises the card handler rather than a shortcut around it. Fires
    // once: later shelves arriving would otherwise re-open the same card while
    // the first collection is still being fetched.
    var hookFired by remember { mutableStateOf(false) }
    LaunchedEffect(autoPlayFirst, state) {
        if (!autoPlayFirst || hookFired) return@LaunchedEffect
        val item = (state as? UiState.Success)?.data
            ?.firstNotNullOfOrNull { shelf -> shelf.items.firstOrNull() }
            ?: return@LaunchedEffect
        hookFired = true
        openCard(item)
    }

    HomeScreen(
        state = state,
        listState = listState,
        title = stringResource(Res.string.listen_now),
        signedIn = false,
        onSignIn = null,
        onItemClick = { item, _ -> openCard(item) },
        onRetry = vm::loadHome,
        refreshing = refreshing,
        onRefresh = vm::refresh,
        pullState = pullState,
        contentPadding = PaddingValues(
            top = topBarContentPadding(),
            bottom = if (song != null) 210.dp else 140.dp,
        ),
        onLoadMore = vm::loadMore,
        loadingMore = loadingMore,
        recentlyPlayedLoading = recentlyPlayedLoading,
    )
}
