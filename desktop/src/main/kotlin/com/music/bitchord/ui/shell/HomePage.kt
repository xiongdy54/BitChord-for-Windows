package com.music.bitchord.ui.shell

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.Song
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
fun HomePage(vm: HomeViewModel, player: PlayerController) {
    val state by vm.home.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val recentlyPlayedLoading by vm.recentlyPlayedLoading.collectAsState()
    val song by player.current.collectAsState()
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(Unit) { vm.loadHome() }

    HomeScreen(
        state = state,
        listState = listState,
        title = stringResource(Res.string.listen_now),
        signedIn = false,
        onSignIn = null,
        onItemClick = { item, _ ->
            item.videoId?.let { id ->
                player.play(
                    Song(
                        videoId = id,
                        title = item.title,
                        artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                        thumbnailUrl = item.thumbnailUrl,
                    ),
                )
            }
        },
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
