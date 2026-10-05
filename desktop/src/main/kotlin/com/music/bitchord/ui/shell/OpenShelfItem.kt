package com.music.bitchord.ui.shell

import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.desktop.playback.PlayerController

/**
 * What a tap on a feed card does, shared by every surface that shows one —
 * the home feed, a genre's shelves, an artist page's carousels.
 *
 * A track plays. A collection opens its page: the redesign's one behavioural
 * change to the feeds, because the phone layout had nowhere for a page to
 * open into and played the collection outright instead — a sidebar app does
 * have somewhere, and "play" is the page's own button now.
 */
fun openShelfItem(
    item: ShelfItem,
    player: PlayerController,
    onOpenDetail: (Destination.Detail) -> Unit,
) {
    when {
        item.videoId != null -> player.play(
            Song(
                videoId = item.videoId,
                title = item.title,
                artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                thumbnailUrl = item.thumbnailUrl,
            ),
        )

        item.browseId != null -> onOpenDetail(
            Destination.Detail(
                kind = collectionKind(item.browseId),
                browseId = item.browseId,
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
            ),
        )
    }
}

/**
 * Which detail shape a browse id asks for. Shelf cards are albums or
 * playlists; YouTube marks an album with the `MPREb` release prefix, and
 * everything else that carries a `VL` playlist id — including an artist's
 * "full song list" playlist — opens as a playlist.
 */
fun collectionKind(browseId: String): BrowseType =
    if (browseId.startsWith("MPREb")) BrowseType.ALBUM else BrowseType.PLAYLIST
