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
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.desktop.playback.PlayerController
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.listen_now
import com.music.bitchord.ui.HomeViewModel
import com.music.bitchord.ui.screens.HomeScreen
import org.jetbrains.compose.resources.stringResource

/**
 * The home feed, wired the way MainActivity wires it: the same title and the
 * same derivation of a card's artist from its subtitle, with the content
 * padding the sidebar shell needs — the toolbar is beside the page now, not
 * over it, so the page starts at its own gutter and reserves room for nothing
 * floating above it.
 *
 * Tapping a track card plays it. Tapping a collection card opens its page —
 * [openShelfItem] has the whole rule; the phone layout played collections
 * outright because it had nowhere for a page to open into.
 */
@Composable
fun HomePage(
    vm: HomeViewModel,
    player: PlayerController,
    onOpenDetail: (Destination.Detail) -> Unit,
    autoPlayFirst: Boolean = false,
) {
    val state by vm.home.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val recentlyPlayedLoading by vm.recentlyPlayedLoading.collectAsState()
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(Unit) { vm.loadHome() }

    fun openCard(item: ShelfItem) {
        openShelfItem(item, player, onOpenDetail)
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
        contentPadding = PaddingValues(top = PAGE_TOP_GUTTER, bottom = 60.dp),
        onLoadMore = vm::loadMore,
        loadingMore = loadingMore,
        recentlyPlayedLoading = recentlyPlayedLoading,
    )
}
