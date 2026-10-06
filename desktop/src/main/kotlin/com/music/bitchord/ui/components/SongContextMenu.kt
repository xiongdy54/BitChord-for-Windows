package com.music.bitchord.ui.components

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.add_to_queue
import com.music.bitchord.desktop.resources.copy_link
import com.music.bitchord.desktop.resources.copy_log
import com.music.bitchord.desktop.resources.like
import com.music.bitchord.desktop.resources.open_album
import com.music.bitchord.desktop.resources.open_artist
import com.music.bitchord.desktop.resources.play_next
import com.music.bitchord.desktop.resources.remove_from_liked
import com.music.bitchord.desktop.resources.start_radio
import org.jetbrains.compose.resources.stringResource

/**
 * The things a row's right-click can ask for — the desktop's answer to
 * upstream's 14-row `SongActionsSheet`, which is a touch-screen bottom sheet
 * and cannot be carried across as it stands. Every entry has a kernel or an
 * endpoint behind it: the queue mutations and the like are already there, and
 * `radio()` has been in the repository since it was ported. The entries the
 * upstream sheet has that nothing here can serve — downloads, playlist
 * add/remove (needs sign-in), the sleep timer, lyric offset — stay absent
 * rather than arriving dead.
 */
enum class SongAction {
    PlayNext,
    AddToQueue,
    ToggleLike,
    StartRadio,
    OpenAlbum,
    OpenArtist,
    CopyLink,

    /** About *this* playback, not the song — only ever offered on the current track. */
    CopyLog,
}

/**
 * What a right-click on [song] may offer, in display order: the queue
 * actions, then the ones that leave the row, then the log.
 *
 * A pure capability filter: the *labels* and the *effects* are the shell's
 * business — this only decides what exists. `OpenAlbum` and `OpenArtist`
 * appear only when the row actually carries the browse id; a menu item that
 * can only say "no" is the dead-button rule's textbook violation. `CopyLog`
 * appears only when the song clicked is the one playing, matching upstream's
 * own rule that it is about *this* playback.
 */
fun songActions(song: Song, isCurrent: Boolean): List<SongAction> = buildList {
    add(SongAction.PlayNext)
    add(SongAction.AddToQueue)
    add(SongAction.ToggleLike)
    add(SongAction.StartRadio)
    if (song.albumId != null) add(SongAction.OpenAlbum)
    if (song.artistId != null) add(SongAction.OpenArtist)
    add(SongAction.CopyLink)
    if (isCurrent) add(SongAction.CopyLog)
}

/**
 * The menu's words, read off the merged resource copy of the app's strings.
 */
class SongActionLabels(
    val playNext: String,
    val addToQueue: String,
    val like: String,
    val removeFromLiked: String,
    val startRadio: String,
    val openAlbum: String,
    val openArtist: String,
    val copyLink: String,
    val copyLog: String,
)

@Composable
fun rememberSongActionLabels() = SongActionLabels(
    playNext = stringResource(Res.string.play_next),
    addToQueue = stringResource(Res.string.add_to_queue),
    like = stringResource(Res.string.like),
    removeFromLiked = stringResource(Res.string.remove_from_liked),
    startRadio = stringResource(Res.string.start_radio),
    openAlbum = stringResource(Res.string.open_album),
    openArtist = stringResource(Res.string.open_artist),
    copyLink = stringResource(Res.string.copy_link),
    copyLog = stringResource(Res.string.copy_log),
)

/**
 * The shell's half of the menu: what each [SongAction] *does*. All effects
 * live behind these lambdas so the menu itself stays a pure surface —
 * [SongContextMenuTest] owns the filter, and the wiring is read off `Main`.
 *
 * `toggleLike` takes the current like state because the toggle's next value
 * is a fact about the row, read fresh each time the menu opens.
 */
class SongActions(
    val playNext: (Song) -> Unit,
    val addToQueue: (Song) -> Unit,
    val toggleLike: (Song, liked: Boolean) -> Unit,
    val startRadio: (Song) -> Unit,
    val openAlbum: (Song) -> Unit,
    val openArtist: (Song) -> Unit,
    val copyLink: (Song) -> Unit,
    val copyLog: () -> Unit,
    /** The track playing right now, or null — [SongAction.CopyLog]'s gate. */
    val currentVideoId: () -> String?,
)

val LocalSongActions = staticCompositionLocalOf<SongActions?> { null }

/**
 * The right-click menu of one track row, over the desktop's own
 * `ContextMenuArea`: the platform detects the secondary click (and the
 * keyboard's menu key), draws the menu near the pointer, and hands the item
 * list a *builder* — evaluated at open time, so a label can never describe a
 * state the tap behind it won't act on: `liked` and "is this the current
 * track" are read fresh, not remembered from when the row was drawn.
 *
 * Layout-neutral by construction: the area wraps its content without sizing
 * of its own, so the row inside measures exactly as it did unwrapped.
 */
@Composable
fun SongContextMenuArea(song: Song, content: @Composable () -> Unit) {
    val actions = LocalSongActions.current
    if (actions == null) {
        content()
        return
    }
    val labels = rememberSongActionLabels()
    ContextMenuArea(
        items = {
            val liked = LikeState.overrides.value[song.videoId] == LikeStatus.LIKE
            val isCurrent = actions.currentVideoId() == song.videoId
            songActions(song, isCurrent).map { action ->
                when (action) {
                    SongAction.PlayNext -> ContextMenuItem(labels.playNext) { actions.playNext(song) }
                    SongAction.AddToQueue -> ContextMenuItem(labels.addToQueue) { actions.addToQueue(song) }
                    SongAction.ToggleLike -> ContextMenuItem(
                        if (liked) labels.removeFromLiked else labels.like,
                    ) { actions.toggleLike(song, liked) }
                    SongAction.StartRadio -> ContextMenuItem(labels.startRadio) { actions.startRadio(song) }
                    SongAction.OpenAlbum -> ContextMenuItem(labels.openAlbum) { actions.openAlbum(song) }
                    SongAction.OpenArtist -> ContextMenuItem(labels.openArtist) { actions.openArtist(song) }
                    SongAction.CopyLink -> ContextMenuItem(labels.copyLink) { actions.copyLink(song) }
                    SongAction.CopyLog -> ContextMenuItem(labels.copyLog) { actions.copyLog() }
                }
            }
        },
    ) {
        content()
    }
}
