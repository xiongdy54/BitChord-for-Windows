package com.music.bitchord.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.desktop.playback.PlayerController
import com.music.bitchord.desktop.resources.Res
import com.music.bitchord.desktop.resources.back
import com.music.bitchord.ui.player.NowPlayingScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The desktop form of MainActivity's ~40-argument call to [NowPlayingScreen].
 *
 * One [PlayerController.state] snapshot feeds the whole page, which is why the screen file
 * could be carried over without renaming a single parameter: every field it reads is the
 * field the original read, and this is the only place in the desktop build where the two are
 * joined up. Keep it that way — a mapping that moves into `NowPlayingScreen.kt` is a mapping
 * that stops being verifiable against the app.
 */
@Composable
fun PlayerPage(
    player: PlayerController,
    state: ShellState,
    /**
     * Where the credits lead: the album page for the title, the artist page
     * for the artist line. The screen hands over the browse id; the shell
     * owns the navigation stack, so the destination is built up there.
     */
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by player.state.collectAsState()
    val shuffle by player.shuffleEnabled.collectAsState()
    val volumePercent by player.volume.collectAsState()
    val overrides by LikeState.overrides.collectAsState()
    // The player cannot be open without a track: `showPlayer` is only set from the mini
    // player's body, and that bar is mounted only while the snapshot has a song (Shell.kt),
    // plus the debug hook, which checks the same thing. So an open overlay whose song has gone
    // is a state that should not outlive its frame — and returning here would leave it alive:
    // nothing drawn, `showPlayer` still true, and Main.kt's Escape handler still consuming the
    // key over the ordinary shell, so the key reads as dead until the next track plays.
    // Clearing the flag is the honest half of the guard; the overlay unmounts with it.
    val song = snapshot.song
    LaunchedEffect(song) {
        if (song == null) state.showPlayer = false
    }
    if (song == null) return

    val (windowWidth, windowHeight) = windowDimensions()

    Box(modifier) {
        NowPlayingScreen(
            song = song,
            isPlaying = snapshot.isPlaying,
            isLoading = snapshot.isLoading,
            // The object, never a value read up here: the playhead ticks twice a second and this
            // scope is the whole player. See `PlaybackPosition`.
            position = player.position,
            durationMs = snapshot.durationMs,
            queue = snapshot.queue,
            queueIndex = snapshot.queueIndex,
            hasPrevious = snapshot.hasPrevious,
            hasNext = snapshot.hasNext,
            repeatMode = snapshot.repeatMode,
            shuffleEnabled = shuffle,
            likeStatus = overrides[song.videoId] ?: LikeStatus.INDIFFERENT,
            onToggleLike = { toggleSongLike(song, overrides[song.videoId] == LikeStatus.LIKE) },
            onPlayPause = player::togglePlayPause,
            onNext = player::next,
            onPrevious = player::previous,
            onSeekFraction = player::seekToFraction,
            onToggleShuffle = player::toggleShuffle,
            onCycleRepeat = player::cycleRepeat,
            onJumpTo = player::jumpTo,
            onRemoveFromQueue = player::removeFromQueue,
            onMoveInQueue = player::moveInQueue,
            // Upstream pressed the player's own swipe gestures down while a queue row was being
            // dragged (app :575), because on Android the two gestures compete for the same vertical
            // drag. A desktop window has no swipe-to-dismiss on the player, so there is nothing to
            // suppress; it renders nothing either, which is what makes an empty lambda honest here
            // rather than a dead control.
            onQueueDragActiveChange = { },
            onClearQueue = player::clearQueue,
            // Task 12 renders this menu — play next, add to queue, like, copy log — out of
            // `state.menuSong` in Shell. The player's ⋮ is upstream's own glyph and is live from
            // the moment that lands; until then this is the only half of it that exists.
            onOpenMenu = { state.menuSong = song },
            onOpenAlbum = onOpenAlbum,
            onOpenArtist = onOpenArtist,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            // `PlayerController.volume` is 0..100 and the bar is 0f..1f, because `ThinSlider` wants
            // the fraction — so the scale is mapped here, at the one seam that knows both sides.
            // `setVolume` coerces to 0..100 itself, so nothing else is needed at the seam, and the
            // write stays continuous with the drag for the reason recorded on the parameter.
            volume = volumePercent / 100f,
            onVolumeChange = { player.setVolume((it * 100).roundToInt()) },
            modifier = Modifier.fillMaxSize(),
        )
        // The player's way back. Esc has always closed this overlay, but a key
        // is not an affordance — the chevron sits where the phone layout's
        // drag-down handle is, over the artwork's backdrop, and the button
        // stays clear of the press-swallowing overlay because a child that
        // consumes a press stops it from ever reaching the parent.
        IconButton(
            onClick = { state.showPlayer = false },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 22.dp, top = 22.dp)
                .size(42.dp)
                .background(Color.White.copy(alpha = 0.12f), CircleShape),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = stringResource(Res.string.back),
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * The window, in dp.
 *
 * `LocalConfiguration` is Android's; on this platform the window's own container size is the
 * equivalent, and it is what makes the original's rule do the work: pass the real width and
 * height down and dragging the window taller than it is wide flips the player to its portrait
 * branch, because [landscapePlayerAvailable] is the app's test, not a desktop one.
 */
@Composable
private fun windowDimensions(): Pair<Dp, Dp> {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return with(density) {
        size.width.toDp() to size.height.toDp()
    }
}

/**
 * Optimistic like, exactly like the original's notification action: paint first, then write.
 *
 * The heart on the player and the row menu's "Like" (Task 12) are the same act, so this is the
 * one copy of it, and Task 12 calls it rather than rewriting it. [LikeState] is the only like
 * source this build has: the library's own ratings are read at load time and an override wins
 * over them, which is why the glyph repaints on the frame the tap lands rather than on the
 * frame the write-back answers.
 *
 * Signing in is a later slice, and unauthenticated `Innertube.rate` throws — which
 * [YtMusicRepository.rate] catches into a failed `Result`. The override is rolled back and the
 * reason goes to the log the menu's "copy log" row carries; no sheet, no toast, nothing that
 * would be a control with no second half.
 */
fun toggleSongLike(song: Song, liked: Boolean) {
    val next = if (liked) LikeStatus.INDIFFERENT else LikeStatus.LIKE
    val previous = LikeState.overrides.value[song.videoId]
    LikeState.set(song.videoId, next)
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        YtMusicRepository.rate(song.videoId, next).onFailure {
            LikeState.set(song.videoId, previous ?: LikeStatus.INDIFFERENT)
            TrackLog.w("like", "rate(${song.videoId}) failed: ${it.message}")
        }
    }
}
