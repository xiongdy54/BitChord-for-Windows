// Constants and geometry above the marker, the `NowPlayingScreen` body below it
// (Task 8 landed the layer, Task 10 appended the body — the append-only contract
// is recorded at the marker). See the body's own header for what the port took
// out and for the three helpers it had to bring with it.
//
// Ported from app/src/main/java/com/music/bitchord/ui/player/NowPlayingScreen.kt
// (the app line is cited above each group). Values, names, units and KDocs
// verbatim. The numbers a test actually pins are PLAYER_GUTTER (30.dp),
// PLAYER_MAX_WIDTH (560.dp), TABLET_PLAYER_MIN_WIDTH (700.dp) and
// LANDSCAPE_PLAYER_MIN_WIDTH (560.dp), each asserted by literal in
// `PlayerGeometryTest`; every other number here is pinned only against the app
// line cited above its group.
//
// Deliberately not carried over, each because the family it serves does not
// ship in this slice:
//   :183, :186  ART_RETRIES / ART_RETRY_DELAY_MS — audio-version switching
//   :193, :196  ALBUM_SETTLE_MS / REVERT_CUE_MS  — canvas + lossless-upgrade path
//   :204, :212  SEEK_SETTLE_*                    — quality-swap seek settling
//   :259        VERSION_PILL_ART_INSET           — the deleted output pill (and
//                                                 unused even in the app)
//   :270, :281  SUBVIEW_STATUS_SCRIM_MIN_ALPHA / MESH_REFRESH_MS — lyrics
//                                                 subviews and the Canvas mesh
//                                                 refresh, both later slices
//   :412-418    LYRICS_CONTROLS_IDLE_MS / SPOTIFY_CANVAS_* /
//               SPOTIFY_DECK_TOP_FADE_FRACTION   — lyrics and motion-cover
//   :359        private var lastControlSpread     — a frame cache of the control
//                                                 layout; it comes with the body
//
// One mechanical difference from upstream, same as PlayerControls.kt:
// TABLET_PLAYER_MIN_WIDTH and LANDSCAPE_PLAYER_MIN_WIDTH are `internal` rather
// than the app's `private`, so the geometry test in another file can name them —
// and does, by literal: 700.dp in the width/gutter group, 560.dp in the landscape
// group beside that group's operator pin. Values unchanged.
package com.music.bitchord.ui.player

import com.music.bitchord.desktop.resources.*

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.playback.QueueTimeline
import com.music.bitchord.ui.components.rememberRemoteArtworkUrl
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.theme.rememberArtworkTopBandLuminance
import com.music.bitchord.ui.theme.topBandScrimAlpha
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

// app NowPlayingScreen.kt:167-173
/**
 * Comfortably over the sleeve's drawn size on a phone, without wasting bytes.
 *
 * A rung on the app-wide ladder rather than a number of the player's own, so a
 * large home-screen widget asks for the same copy — see [PLAYER_ART_PX].
 */
internal const val ART_PX = PLAYER_ART_PX

// app NowPlayingScreen.kt:214-216
private val THUMB_SIZE = 54.dp
private val HEADER_HEIGHT = 60.dp
private val ART_TITLE_GAP = 20.dp

// app NowPlayingScreen.kt:217-242
/**
 * How long the sleeve takes to travel the whole way between the full player and
 * the queue's header.
 *
 * Spent in proportion rather than in full: a drag released four fifths of the
 * way up has a fifth of the journey left and gets a fifth of the time for it.
 * Only the toggle, which travels end to end, ever spends all of it.
 */
private const val QUEUE_TRAVEL_MS = 420
/**
 * How far up the sleeve has to have been dragged for a release to carry on
 * opening the queue rather than falling back, as a share of the sleeve's travel.
 *
 * Well under half, because the gesture is only ever *started* deliberately —
 * there is nothing else an upward drag on the artwork could have meant — so the
 * doubt a halfway line exists to settle isn't there.
 */
private const val QUEUE_CARRY_FRACTION = 0.3f
/**
 * How fast a release has to be moving, in pixels a second, to decide the queue
 * on its own and overrule [QUEUE_CARRY_FRACTION].
 *
 * A flick is a whole gesture in its own right: it says "open" without ever
 * asking the finger to travel, and the distance it covered is beside the point.
 */
private const val QUEUE_FLICK_VELOCITY = 450f

// app NowPlayingScreen.kt:243-252
/**
 * The handle strip above the artwork, which always hands drags to the sheet.
 *
 * It isn't the only place that does — the artwork and the credits under it pass
 * theirs on as well, which is what makes the whole top of the player closable
 * rather than just its topmost 32dp. See the dismiss band in `NowPlayingScreen`.
 */
private val DISMISS_STRIP_HEIGHT = 32.dp
/** The breathing room above the sleeve, needed twice: once to apply, once to measure past. */
private val ART_BOX_TOP_PAD = 8.dp

// app NowPlayingScreen.kt:260-267
/**
 * Share of the motion-artwork banner's height given over to its dissolve.
 *
 * Generous on purpose: the banner has no card edge to stop at, so anything
 * short enough to still be reading as artwork where it ends reads as a picture
 * that was cut off rather than one that ran out.
 */
private const val HERO_FADE_FRACTION = 0.42f

// app NowPlayingScreen.kt:283-291
/** The player's side margin. Scrollable panels reach back across it. */
internal val PLAYER_GUTTER = 30.dp
/**
 * How wide the player's content is ever allowed to get. A sleeve and a volume
 * slider stretched right across a tablet aren't a bigger player, just a coarser
 * one; past this the column stops growing and centres itself instead. Phones
 * are narrower than this, so for them it does nothing.
 */
internal val PLAYER_MAX_WIDTH = 560.dp

// app NowPlayingScreen.kt:292-302
/**
 * The width from which the player counts as tablet-sized: its backdrop is
 * the full-cover blur in every state rather than the phone's seam-aware
 * mesh, and Settings keeps the full-bleed artwork switch listed — see
 * [fullBleedArtworkAvailable].
 *
 * 700dp is the figure the docked-player layout used to call "tablet sized"
 * (a 360dp page beside a 340dp pane). That layout is gone; the number stays,
 * so removing it moved no breakpoint.
 */
internal val TABLET_PLAYER_MIN_WIDTH = 700.dp

// app NowPlayingScreen.kt:304-316
/**
 * The least a landscape window has to offer before the player splits into its
 * two columns: enough that each half still holds what it is given — the sleeve
 * above the lyrics / output / queue row on the left, the credits and transport
 * on the right — with the bottom row's three-up capsule still fitting across
 * the narrower half.
 *
 * Low enough to take in a phone turned sideways, which is the point: a phone in
 * landscape and a tablet in landscape are the same shape, and they get the same
 * layout rather than the portrait player squashed into a window it was never
 * drawn for.
 */
internal val LANDSCAPE_PLAYER_MIN_WIDTH = 560.dp

// app NowPlayingScreen.kt:318-339
/**
 * The artwork's own play/pause/scrub pose in the landscape layout. Three flat
 * scales and one priority rule: paused always wins outright over a scrub in
 * progress, rather than the two combining — there is one artwork, in one of
 * three settled poses, never a blend of two.
 */
private const val ARTWORK_EXPANDED_SCALE = 1f
private const val ARTWORK_PAUSE_SHRINK_SCALE = 0.88f
private const val ARTWORK_DRAG_SHRINK_SCALE = 0.94f

/**
 * The curve and duration those three poses move between — an ease-out cubic
 * over 500ms rather than a spring. A spring reads wrong for a press-and-release
 * gesture specifically: it visibly lags a quick scrub and keeps settling after
 * the finger has already lifted.
 *
 * The phone layout keeps its own bouncy spring ([artScale]) — it is answering a
 * different thing there, a sleeve that also collapses into a header, and the
 * bounce is the signature.
 */
private val ArtworkScaleEasing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
private const val ARTWORK_SCALE_DURATION_MS = 500

// app NowPlayingScreen.kt:341-349
/**
 * How far a tall screen is allowed to push the transport from the blocks either
 * side of it.
 *
 * The spare height has to land somewhere, and above and below the play button is
 * where it reads as room rather than as a hole. Past this it stops reading as one
 * group of controls, so the rest goes back to the artwork block.
 */
private val CONTROL_GAP_SPREAD_MAX = 24.dp

// app NowPlayingScreen.kt:361-409
/**
 * Whether the full-bleed artwork switch is worth listing in Settings for a
 * window this wide. Public so the settings sheet can leave the switch out
 * entirely where it would do nothing.
 *
 * A window narrow enough that the player fills it is where the setting
 * acts: edge to edge there means the artwork *is* the screen. The tablet
 * half is left from the docked player, whose phone-width pane ran the
 * artwork edge to edge too; with the pane gone the portrait player no
 * longer goes full bleed at these widths, but the switch is still listed
 * there, unchanged, until that is decided on its own.
 */
fun fullBleedArtworkAvailable(windowWidth: Dp): Boolean =
    playerFillsWindow(windowWidth) || tabletSizedPlayer(windowWidth)

/**
 * Whether a player given the whole of a window this wide is still narrow enough
 * to run its artwork edge to edge.
 */
private fun playerFillsWindow(windowWidth: Dp): Boolean =
    windowWidth <= PLAYER_MAX_WIDTH + PLAYER_GUTTER * 2

/**
 * Whether the player is tablet-sized — see [TABLET_PLAYER_MIN_WIDTH].
 *
 * [windowWidth] is the width of the *window*, measured rather than read off
 * `Configuration.screenWidthDp`: in a freeform or desktop window that can
 * report the display instead of the window, and it lands a beat late when
 * the window is dragged.
 */
private fun tabletSizedPlayer(windowWidth: Dp): Boolean =
    windowWidth >= TABLET_PLAYER_MIN_WIDTH

/**
 * Whether the player takes its landscape shape: the sleeve and the lyrics /
 * output / queue row in the left column, and the right column showing the
 * credits and transport, the lyrics or the queue — one of the three at a time.
 *
 * The same answer for a tablet and a phone on its side. Both are asked the
 * same question, the window's own proportions, rather than what kind of
 * device this is.
 *
 * Width alone isn't enough: a tablet held upright can be as wide as a phone
 * held sideways, and the two-column layout is a landscape shape, not a "wide
 * enough" one. Upright, every device gets the portrait player — a tall window
 * is the shape it was drawn for.
 */
fun landscapePlayerAvailable(windowWidth: Dp, windowHeight: Dp): Boolean =
    windowWidth > windowHeight && windowWidth >= LANDSCAPE_PLAYER_MIN_WIDTH

// =====================================================================================
// TASK 10 — READ THIS BEFORE EDITING THIS FILE.
//
// Everything above is the shared constants/geometry layer that Task 8 landed so
// `PlayerQueue.kt` could compile — `PLAYER_GUTTER` is what that file's three panel
// paddings read (app PlayerQueue.kt:281, :292, :347). `TABLET_PLAYER_MIN_WIDTH`
// and `LANDSCAPE_PLAYER_MIN_WIDTH` are `internal` because `PlayerGeometryTest`
// asserts each by literal (700.dp, 560.dp); no other number in this file is read
// by a test, so Task 10's body must not assume one guards the rest of them.
//
// Task 10 ports the body of `NowPlayingScreen` from
// `app/src/main/java/com/music/bitchord/ui/player/NowPlayingScreen.kt` by APPENDING
// it below this marker, together with the private helpers the body needs. It must
// NOT replace this file with a copy of the app's file: that would silently drop the
// constants `PlayerQueue.kt` depends on and re-take the deletions recorded at the
// top of this file (lyrics, AutoPlay, audio output, Listen Together, Canvas).
// Where the app's file and this one already declare the same name, this one wins —
// add the body's own declarations only, and keep the values above untouched unless
// the app changed them.
// =====================================================================================

// -------------------------------------------------------------------------------------
// THE BODY — app NowPlayingScreen.kt:507-2968, plus the three helpers it reads
// (`dragQueueIn`, `PlayerArtwork`/`rememberPlayerArtwork`,
// `PlayerScrub`/`rememberPlayerScrub`). `dragQueueIn` sits after the app's body
// range in the same file, and is `private` there; the other two are in the app's
// `PlayerState.kt`, which no task in this plan carried, and are `internal` there
// for exactly one reason — that file is not their caller's. Carrying them with
// their only caller put them in this file, so all four keep the app's `private`:
// nothing outside this file reads them. `LandscapePlayer.kt` names
// `PlayerArtwork` in prose only, and takes the sleeve as the plain
// `ImageRequest`/`Boolean` pair it is handed.
//
// Four things left, in order of how much of the file they took:
//
//  * **Lyrics** (spec §6, slice 3) — the panel, the one-line strip over the scrubber,
//    the romanize/translate discs, the provider and offset sheets, `lyricsOpen` and
//    `lyricsControlsOpen` and the five-second stand-down that drove them, the
//    position-with-offset lambdas, and the third pane of the landscape column.
//  * **Motion artwork** (spec §3.3) — `CanvasArtworkPlayer`, the hero *clip*, every
//    frame-derived colour, `canvasRendered`/`canvasFrame`/`canvasCover`/`canvasAspect`,
//    the Spotify presentation state and the sliding glass deck that came in with it —
//    the deck's own going cost a live behaviour, the queue-scroll collapse, which is
//    named at the queue's call site rather than glossed here —
//    and the two `heroMode` inputs that were not the still cover's own. The static
//    full-bleed hero `AsyncImage` stays: it is the fourth layer of the backdrop stack,
//    and its `DstIn` bottom dissolve is `HERO_FADE_FRACTION`'s whole reason for
//    existing. What survives of `heroMode` is the still-art half of it — see its
//    declaration.
//  * **Android-only** (spec §3.3) — `PlayerBackHandler` and `OverlayBack` with all
//    seven call sites (the queue is closed by Task 11's Esc and by the row's own
//    glyph), `StatusBarIcons`, `LocalView`/`keepScreenOn`, the dead `LocalContext`,
//    and the `android.graphics.Bitmap` the frame capture carried. `Build.VERSION` is
//    gone with the deck it gated, which leaves no SDK check anywhere in this file.
//    The two inset calls at app `:1196` and `:1977-1978` were kept on purpose: CMP
//    ships them, they resolve to zero here, and slice 1's record says so.
//  * **Login, party, output, AutoPlay, the version switch** — `signedIn`,
//    `accountName`, `controlsLocked`, `onBlockedControl`, the output and members
//    sheets, `autoplayEnabled` and its toggle, `audioVersionSwitching`,
//    `qualityUpgraded` and the rollback cue it lit. Their parameters are gone from
//    the table, so their readers had to go too: the like glyph keeps only upstream's
//    second gate (`localUri`), the transport keeps only the queue's own ends, the
//    scrubber's `loading` has nothing left to be true for, and `InlineQueue` is
//    handed `autoplayEnabled = false` — the AUTOPLAY tier renders because nothing
//    fills it, which is decision 6's stated implementation.
//
// Settings this file used to read and does not have on this platform — desktop
// `AppSettings` carries seven fields, none of them the player's — so each of these
// takes the value the Android default would have given it, and says so at the site:
// `lastPlayerScreen` (the player always opens on itself, so `queueOpen` starts
// false), `fullBleedArtwork` (true), `legacyMeshGradient` (false, which is why
// `MeshGradientBackground` has no call site here — see the backdrop),
// `hideSongStatus` (false), `smartTransitionWindow` (null),
// `versionAlignmentInProgress` (false), and `hideVolumeBar` (false — the bar is
// drawn, which is why there is no branch over it below).
//
// **The volume bar is mounted, and `volume` / `onVolumeChange` are the only
// additions to the parameter table.** Upstream never passed a level in: it read one
// from `rememberPlayerVolume()`, which rides Android's `AudioManager` behind a
// `ContentObserver` on `Settings.System` (app `PlayerState.kt:284-354`) — a hook this
// port has no equivalent of, and no composition local to stand in for it. Desktop's
// volume is on `PlayerController` (`volume: StateFlow<Int>` in percent,
// `setVolume(percent: Int)`), so Task 11's adapter hands it down the same way it hands
// down every other callback here, and maps the scales at that seam. Both layouts
// render the bar. `AppSettings.hideVolumeBar` is not ported — no settings sheet this
// slice — so upstream's hide branch and the `VOLUME_ROW_HEIGHT` spacer that stood in
// for it are gone, which leaves that Task 7 constant with no caller; see the portrait
// call site. What upstream's `release()` still does, and why desktop needs none of
// it, is at [onVolumeChange].
// -------------------------------------------------------------------------------------

// app NowPlayingScreen.kt:175-186 — the constants layer left these two out on the
// record that they serve the audio-version switch. They do not: they are the cover's
// own load retries, and `rememberPlayerArtwork` below is their only caller. Task 9's
// `ArtworkMeshBackdrop.kt:282-285` keeps this file's mesh retries for the same reason
// and points at this guard by name, so the two paths stay in step.
/**
 * How many further goes a cover that failed to load gets.
 *
 * Small on purpose. This is here for the connection that drops for a moment or
 * the request that loses a race with the app coming back to the foreground, not
 * for a track whose artwork has genuinely gone: past a few tries the answer is
 * not going to change, and the placeholder tile is the honest thing to draw.
 */
private const val ART_RETRIES = 3

/** How long to leave it before trying a failed cover again. */
private const val ART_RETRY_DELAY_MS = 1_500L

// app NowPlayingScreen.kt:198-212 — same note: dropped by the constants layer as
// quality-swap settling, but the released scrub handle is what reads them, and the
// scrubber ships here.
/**
 * How close the player's reported position has to get to a released scrub
 * handle before the handle stops being drawn where it was dropped. Wide enough
 * to swallow a coarse progress tick, tight enough that the handle doesn't hand
 * over while it is still visibly wrong.
 */
private const val SEEK_SETTLE_TOLERANCE_MS = 1_500L

/**
 * How long that handle is held at the drop point regardless. A backstop, not a
 * schedule: a seek normally settles in a tick or two, and this only decides how
 * long a seek that never settles can freeze the bar for. Generous enough that a
 * slow buffer still hands over smoothly rather than snapping back.
 */
private const val SEEK_SETTLE_TIMEOUT_MS = 4_000L

// app NowPlayingScreen.kt:268-270. The constants layer records this one as a lyrics
// subview number. The queue is a subview too, and it is the only one left.
/** A modest floor while a subview replaces the hero with its artwork-derived mesh. */
private const val SUBVIEW_STATUS_SCRIM_MIN_ALPHA = 0.40f

// app NowPlayingScreen.kt:350-359 — the frame cache of the control layout, which the
// constants layer left for the body because only the body reads it.
/**
 * The spread [NowPlayingScreen] settled on the last time it was laid out.
 *
 * It follows from the window, so it is very nearly the same answer on every open
 * — and the player is torn down with its sheet, so without this the first frame
 * of each open would show the unspread gaps and then step to the real ones. Only
 * a head start: the frame after re-derives it either way. A plain var because
 * that is all it is, a cache of a measurement, not state anything observes.
 */
private var lastControlSpread: Dp = 0.dp

/**
 * Apple Music's Now Playing, closely: artwork that shrinks when paused, a
 * hairline scrubber with elapsed / remaining either side, oversized transport
 * glyphs, a volume capsule flanked by speaker icons, and the queue along the
 * bottom.
 *
 * Upstream's bottom row also held lyrics and the output/pair, and its artwork
 * could be a looping clip rather than a cover; both are named above.
 */
@Composable
@OptIn(ExperimentalHazeApi::class, ExperimentalHazeMaterialsApi::class)
fun NowPlayingScreen(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    /**
     * The playhead, read only where it is drawn — never here. Passed as the
     * object rather than its value because a value read at this level is a
     * read in this function's own scope, and it ticks twice a second: the whole
     * player recomposed with it. See [PlaybackPosition].
     */
    position: PlaybackPosition,
    durationMs: Long,
    queue: List<Song>,
    queueIndex: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    likeStatus: LikeStatus,
    onToggleLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    /**
     * Seek to a fraction of the track, for the scrubber — the only seek this
     * screen asks for.
     *
     * Upstream had a sibling `onSeek: (Long) -> Unit` for a time. It is gone here,
     * and not because a parameter was tidied away: its single call site in the app
     * (`:652`) was `seekToLyric`, the tapped lyric line's time, and it died with
     * that family. Everything else already routes through fractions — upstream's
     * scrub release at `:1298` and [onScrubFinished] below both call
     * `scrub.release(onSeekFraction)`, and `onJumpTo` moves between rows rather
     * than within one.
     *
     * Why a fraction survives at all: converting it here would use this screen's
     * cached duration, which lags a track change by however long the session takes
     * to report the new one — long enough to drop the handle on a bar still scaled
     * to the previous song and seek to the wrong fraction of the current one. The
     * conversion belongs wherever the freshest duration is.
     */
    onSeekFraction: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    /**
     * A queue row started or stopped being dragged.
     *
     * Upstream told the party's sync about it so a reorder could hold its publish
     * until the row was dropped. There is no party here, so this is handed to
     * [InlineQueue] unchanged and Task 11's adapter decides what it means — an
     * empty lambda costs nothing.
     */
    onQueueDragActiveChange: (Boolean) -> Unit = {},
    onClearQueue: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    /** The width of the window the player is in — see [fullBleedArtworkAvailable]. */
    windowWidth: Dp,
    /**
     * The window's height, alongside [windowWidth] — needed for exactly one
     * thing: telling a portrait window apart from a landscape one, in
     * [landscapePlayerAvailable]. Width alone can't; a big tablet's portrait
     * width comfortably clears a phone's landscape width.
     */
    windowHeight: Dp,
    /**
     * The level the volume bar shows, `0f..1f`.
     *
     * **A desktop-only addition to a table otherwise frozen for Task 11**, and the
     * reason is upstream's own plumbing: the Android screen never passed volume in
     * at all. It read it from `rememberPlayerVolume()` (app `:952`), a hook over
     * `AudioManager` with a `ContentObserver` on `Settings.System` behind it (app
     * `PlayerState.kt:284-354`). Neither exists on this platform, and there is no
     * composition local to lean on, so the controller that owns the level hands it
     * down through these two parameters exactly as it hands down every other
     * callback here — desktop keeps it as `volume: StateFlow<Int>` (percent) beside
     * `setVolume(percent: Int)`, and the adapter maps the two scales.
     *
     * A plain `Float` rather than the `() -> Float` [VolumeRow] takes: upstream's
     * closure existed because its level was a tweened `Animatable` living outside
     * composition, so the bar could read it without the screen recomposing. Read
     * from a parameter that saving is not available, so the lambda here keeps the
     * shape the row asks for without pretending at the savings.
     */
    volume: Float,
    /**
     * The level the bar was dragged to, `0f..1f`, on every step of the drag.
     *
     * Continuous, because upstream's is: `PlayerVolume.drag` writes the stream on
     * each `onValueChange` (app `PlayerState.kt:299-308`), never on release. What
     * upstream passed as `onValueChangeFinished` — `volume::release` (app `:1301`) —
     * commits nothing either: it only clears a `dragging` flag whose whole job was
     * to stop the observer's tween from fighting the finger when a hardware key
     * moved the stream mid-drag. Desktop has no second writer of the level and no
     * system panel to observe, so there is nothing to arbitrate and the two call
     * sites below pass an empty lambda for it.
     */
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = rememberHaptics()

    // Remote tracks whose art lives inside the file resolve it here, once —
    // every surface below reads the same value rather than each triggering
    // its own extraction.
    val remoteArt = rememberRemoteArtworkUrl(song)
    // This is produced by the palette's existing 128 px decode and cache. It
    // samples the upper band rather than the whole sleeve because that is what
    // lies beneath the status bar when the player expands to full bleed.
    val artTopLuminance = rememberArtworkTopBandLuminance(remoteArt, ART_PX)
    val artworkStatusScrimAlpha = topBandScrimAlpha(artTopLuminance)

    // Upstream also owned the window's status-bar glyphs from here. A desktop
    // window has no status bar to own, so the scrim below is the only thing left
    // of that contrast plan.

    // Kept local to the player: a modal player is not in the page's Haze
    // source tree, so it needs its own source for the same frosted material as
    // the bottom navigation pill.
    val playerHaze = remember { HazeState() }

    val scrub = rememberPlayerScrub(song.videoId, position, durationMs)
    // Read by the scrubber itself — see [PlayerScrubber.shown].
    val shown: () -> Float = { scrub.shown(position.positionMs, durationMs) }
    // Back restarts the track rather than stepping back once it is a few
    // seconds in. Derived, so this scope hears about the playhead once, when
    // it crosses that point, rather than on every tick.
    val pastRestartPoint by remember(position) {
        derivedStateOf { position.positionMs > QueueTimeline.BACK_RESTARTS_AFTER_MS }
    }
    // The queue lives inside the player, Apple-style, rather than in a sheet.
    //
    // Upstream read the panel it last showed out of `AppSettings.lastPlayerScreen`
    // when this instance was created, so a reopened player came back to the same
    // surface. That setting is not on this platform (its setter was the sheet the
    // setting lives in, which is a later slice), so the player opens on itself —
    // which is the Android default the same read fell back to.
    var queueOpen by remember { mutableStateOf(false) }
    val queueSlide = remember { mutableFloatStateOf(0f) }
    // Expensive, song-scoped preparation is deliberately staggered. The small
    // backdrop decode runs after the track hand-off has settled; the queue is
    // then composed offscreen on a separate beat rather than competing with the
    // song change. Opening the panel early bypasses its wait.
    //
    // Upstream had a beat in the middle for the lyric sheet's layout; lyrics are
    // a later slice, so the queue moved up into its place rather than keeping a
    // stage that would never be read.
    var playerPrewarmStage by remember(song.videoId) { mutableIntStateOf(0) }
    LaunchedEffect(song.videoId) {
        delay(600)
        playerPrewarmStage = 1 // 128px backdrop decode + CPU blur
        delay(650)
        playerPrewarmStage = 2 // queue layout and initial scroll
    }
    // Whether the queue list is actively mid-scroll. The player's own swipe
    // gestures — skip-by-drag and the dismiss band — are suppressed for as long
    // as it is true, so a scroll that grazes past the list's edge can never be
    // misread as a drag meant for the player underneath it. Reset whenever the
    // panel closes, since a list scrolled mid-transition out never gets a
    // matching "stopped scrolling" event of its own.
    var queueScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(queueOpen) { if (!queueOpen) queueScrolling = false }
    val panelScrolling = queueScrolling
    val toggleQueue: () -> Unit = { queueOpen = !queueOpen }

    // 0 = full sleeve, 1 = queue. Everything that moves reads off this.
    //
    // Plain state driven by an animation rather than [animateFloatAsState],
    // because it has two drivers and only one of them is an animation: the
    // toggle at the foot of the player, which travels end to end, and a finger
    // dragging the sleeve upward, which sets it outright. An animation keyed on
    // [queueOpen] cannot be pushed around mid-flight by a drag — and a drag that
    // could only move the *target* would have nothing to show for itself until
    // it was released, then jump from wherever the animation had got to.
    //
    // Read only inside layout and draw lambdas and the thresholds below, never
    // as a value here — see [p].
    // Whether a finger is on the sleeve right now. Parks the settle below rather
    // than leaving the two to write the same value on alternate frames.
    var queueDragging by remember { mutableStateOf(false) }
    // Bumped when a drag hands the value back, so the settle runs again even
    // though [queueOpen] may not have moved: a swipe that gave up short of
    // [QUEUE_CARRY_FRACTION] has to fall back to 0 just as surely as one that
    // carried has to finish reaching 1.
    var queueReleased by remember { mutableIntStateOf(0) }
    LaunchedEffect(queueOpen, queueDragging, queueReleased) {
        if (queueDragging) return@LaunchedEffect
        val target = if (queueOpen) 1f else 0f
        val from = queueSlide.floatValue
        if (from == target) return@LaunchedEffect
        animate(
            initialValue = from,
            targetValue = target,
            animationSpec = tween(
                durationMillis = (QUEUE_TRAVEL_MS * abs(target - from)).roundToInt(),
                easing = FastOutSlowInEasing,
            ),
        ) { value, _ -> queueSlide.floatValue = value }
    }

    // Horizontal fling anywhere on the player skips tracks; the artwork
    // follows the finger so the gesture has something to hold on to.
    val swipeThreshold = with(density) { 72.dp.toPx() }
    // Where the finger has dragged the sleeve to, and the sleeve springing
    // after it. What animateFloatAsState does inside — a conflated channel of
    // targets, each chased from the current velocity — but fed straight from
    // the gesture: as state read here, every pointer move recomposed the whole
    // player just to hand the spring a new target, when the only readers are
    // draw-phase layers.
    val swipeSettle = remember { Animatable(0f) }
    val swipeTargets = remember { Channel<Float>(Channel.CONFLATED) }
    LaunchedEffect(swipeTargets) {
        for (target in swipeTargets) {
            val newest = swipeTargets.tryReceive().getOrNull() ?: target
            launch {
                if (newest != swipeSettle.targetValue) {
                    swipeSettle.animateTo(newest, spring(stiffness = Spring.StiffnessMediumLow))
                }
            }
        }
    }
    val setSwipeOffset: (Float) -> Unit = { swipeTargets.trySend(it) }
    // The skip hint under the sleeve only needs composing while there is a
    // drag to hint at, and which way it points; its fade is drawn, not composed.
    val swipeHintShown by remember(swipeThreshold) {
        derivedStateOf { abs(swipeSettle.value) / swipeThreshold > 0.01f }
    }
    val swipeHintNext by remember { derivedStateOf { swipeSettle.value > 0f } }

    // Signature Apple Music touch: the sleeve shrinks back while paused.
    val artScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.86f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "artScale",
    )

    // 0 = the ordinary square sleeve, 1 = the artwork as a full-bleed banner.
    // Both states collapse the header, but the banner only ever shows over a
    // settled player: opening the queue hands the sleeve back its card first.
    // How collapsed the sleeve is, whichever surface asked for it.
    val animatedCollapse = animateFloatAsState(
        targetValue = if (queueOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "sleeveCollapse",
    )
    // A queue tap/drag already owns a 420ms progress value. Reusing it avoids
    // driving this large layout from two independent animations on every frame.
    //
    // A function rather than a value. It moves on every frame of a 420ms
    // collapse and of a finger dragging the queue, and read here as a value it
    // recomposed this whole function on each of those frames — the sleeve, the
    // credits, both panels and the controls, just to move a few of them. Layout
    // and draw lambdas call it where they place or paint; composition only asks
    // the thresholds below, each of which changes once per collapse.
    val p: () -> Float = {
        val queueOwnsCollapse =
            queueOpen || queueDragging || queueSlide.floatValue > 0.001f
        if (queueOwnsCollapse) queueSlide.floatValue else animatedCollapse.value
    }
    val collapseAtRest by remember { derivedStateOf { p() == 0f } }
    val collapsePastSettling by remember { derivedStateOf { p() >= 0.01f } }
    val collapsePastHalf by remember { derivedStateOf { p() >= 0.5f } }
    val collapseAlmostDone by remember { derivedStateOf { p() >= 0.999f } }
    val collapseDone by remember { derivedStateOf { p() >= 1f } }
    val queueShowing by remember { derivedStateOf { queueSlide.floatValue > 0.01f } }

    // Whether the sleeve has finished getting out of the way, and how far the
    // panel that replaces it has faded up since.
    //
    // The queue list is one of the two most expensive things this screen can
    // compose — building a lazy list with drag-reorder state per row — and it
    // used to be composed on the frame the panel was asked for, which is the
    // frame the 420ms collapse above starts on. That put the single heaviest
    // composition of the whole screen directly on top of the one animation the
    // eye is following, and it read as the open stuttering.
    //
    // Held back until [p] has actually arrived, the expensive frame lands while
    // nothing is moving, where a dropped frame costs nothing to look at, and the
    // panel then fades up on its own short curve. The open is a little longer end
    // to end and visibly smoother for it.
    //
    // Declared out here rather than beside the panel on purpose: an
    // [animateFloatAsState] created at the moment its target becomes true is
    // created *at* that target and has nothing left to animate. Living above the
    // panel, this one is already at 0 when it mounts.
    val panelsSettled = collapseDone
    val panelFade by animateFloatAsState(
        targetValue = if (panelsSettled) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "panelFade",
    )

    // Full-bleed is a phone idiom. What the width has to rule out is a player
    // running a foot wider than the column of controls under it — edge to edge
    // meaning "a picture, and separately some controls" rather than "the
    // artwork *is* the player".
    //
    // Upstream ORed a second answer in here — a Spotify clip is the background
    // whatever the preference says — and ANDed the user's own
    // `AppSettings.fullBleedArtwork` switch in. The clip is not in this slice and
    // the switch has no desktop setting sheet yet, so what is left is the width
    // rule, with the switch taken at its default (on).
    val heroMode = playerFillsWindow(windowWidth)

    // Whether there's a still image to blow out — a placeholder tile is a card
    // or it is nothing, and going full-bleed with one would just tint the top
    // third of the screen.
    //
    // Keyed on the artwork rather than on the track, because that is what it
    // actually describes and because only Coil can set it back to true. Two
    // tracks off one album share a cover, so skipping between them leaves the
    // request below byte-identical: the painter keeps the Success it already
    // had and never re-emits, so the `onState` that is the sole writer here
    // never fires again. Keyed on the track this reset to false and stayed
    // there, which pinned the sleeve fully opaque (see the alpha it feeds) on
    // top of an equally opaque banner — the same cover drawn twice, card and
    // full-bleed at once. Keyed on the cover there is nothing to reset: the
    // bitmap really is still loaded, so the state stays true and the two
    // layers go on trading places as they should.
    val art = rememberPlayerArtwork(remoteArt)
    // Sticky, unlike [PlayerArtwork.loaded]: the banner is the shape of the player
    // rather than a property of the track in it. Waiting on each new cover would
    // collapse the banner into a card and blow it back out on every skip — twice
    // the length of the whole screen's worth of movement for a change the artwork
    // itself already announces. The frame stays; the cover arrives in it, fading
    // in as Coil fades in everywhere else.
    var heroSettled by remember { mutableStateOf(false) }
    // Success from the banner's own painter, rather than from the separate
    // sleeve painter. Sharing one ImageRequest lets Coil share its cached
    // bitmap, but it does not make two AsyncImage painters enter Success in
    // the same frame. The sleeve must not hand over to a banner which is still
    // empty just because its own painter finished first.
    var heroArtLoaded by remember(art.url, heroMode) { mutableStateOf(false) }
    LaunchedEffect(art.loaded) {
        if (art.loaded) heroSettled = true
    }
    // The backdrop's colours, taken off the artwork's own arrangement rather than
    // quantised out of it — see [ArtworkMesh].
    //
    // Upstream kept the older quantiser's backdrop behind
    // `AppSettings.legacyMeshGradient`, and this file's either/or branch is what
    // chose between them. That switch defaults off upstream (`AppSettings.kt:526`)
    // and has no desktop setting sheet to flip it, so the branch resolves to
    // upstream's own default and `MeshGradientBackground` — which came over with
    // slice 1 and is drawn by the home screen — goes uncalled here. The mesh is
    // the first layer the player stands on, exactly as it is on an Android phone
    // with its settings untouched.
    val artMesh = key(song.videoId) { rememberArtworkMesh(remoteArt, null, ART_PX) }
    // Whether the banner is the presentation at all: full-bleed is on, and there
    // is something to blow out. The collapse is deliberately *not* part of this
    // — see [heroVisible].
    val heroT = animateFloatAsState(
        targetValue = if (heroMode && (art.loaded || heroSettled)) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "heroCanvas",
    )

    /**
     * How much of the banner is actually on screen: its own fade, dissolved by
     * the collapse rather than after it.
     *
     * The collapse used to be a threshold on this animation's *target* — the
     * banner was told to go once [p] passed a half. That chained two 420ms
     * animations end to end when they should have been the same one: for the
     * first half of the collapse the banner sat at full size and full opacity
     * with nothing appearing to move, since the card shrinking behind it is
     * transparent while the banner is up; then the card finished collapsing and
     * a full-screen banner cross-dissolved into a finished thumbnail. Two sizes
     * of the same artwork on screen at once, which is what made every trip in
     * and out of a panel look wrong.
     *
     * Multiplied by the collapse instead, the banner goes as the card shrinks:
     * one movement, and the card is fading in the whole way down.
     */
    val heroVisible: () -> Float = { heroT.value * (1f - p()) }
    val heroShowing by remember { derivedStateOf { heroVisible() > 0.001f } }
    // How tall that banner is, worked out down in the layout where the sleeve's
    // own geometry is known. Zero until the first measure, which is fine: there
    // is nothing to show that early either.
    //
    // The brief for this port asked for a fixed square-cover height here, on the
    // reading that the value was canvas-driven. It is not: upstream measured it
    // as the sleeve's own bottom edge (`bannerBottom` below, app `:2300-2317`),
    // which is the one line the layer stack has to agree on — the mesh's seam and
    // the credits' position both hang off it. A constant would put a full-bleed
    // cover's bottom somewhere the card's is not, so the measurement stays. What
    // *was* canvas-driven here was the second answer upstream kept alongside it —
    // the bottom of a contained portrait clip (`renderedCanvasBottom`) — and that
    // one is gone, which is why the seam has only the hero's own height left.
    var heroHeight by remember { mutableStateOf(0.dp) }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // What sits between the status bar and the artwork: the drag strip. Read
    // in three places — the strip itself,
    // the scrim drawn over it and the banner's own height — which all have to
    // agree or the artwork and the credits under it move.
    val topStrip = DISMISS_STRIP_HEIGHT

    // The band of the player a vertical drag belongs to rather than to whatever
    // is under it: from the top of the artwork to the bottom of the credits, in
    // root coordinates. Everything in between is one block — the full sleeve
    // with the title and artist beneath it — and a drag on it closes the player
    // downwards and opens the queue upwards.
    //
    // Read off the layout rather than recomputed, so it stays the block's own
    // shape whatever the screen: a height-bound sleeve on a tablet, a full-bleed
    // banner on a phone.
    //
    // Only ever the *expanded* block, though. Once a panel is up the band is not
    // this pair at all but the header, worked out from the state instead — see
    // the gesture below. The two edges do travel with the sleeve as it collapses,
    // which reads like the band could simply follow them the whole way, and that
    // is exactly what went wrong: the sleeve takes [QUEUE_TRAVEL_MS] to get
    // there, and for that whole half second the queue was already listed and
    // scrollable underneath a band still lying across it. A drag on a row came
    // out as the player closing.
    //
    // Bare numbers rather than a rect: the band runs the full width of the
    // player either way, and on a height-bound sleeve the bare backdrop down
    // each side of it should close the player too — it is part of the same
    // gesture, and a hole there would be a strip the finger mysteriously
    // slides off.
    //
    // Both start at zero, which is a band with no height and so no hole at all
    // until the first layout pass. There is nothing on screen to drag then
    // either.
    var dismissBandTop by remember { mutableFloatStateOf(0f) }
    var dismissBandBottom by remember { mutableFloatStateOf(0f) }
    // The suppressing Column's own coordinates, to put a pointer's local
    // position into the same space as the two edges above.
    var dismissBandSpace by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Where the caption above the credits leads. Upstream had three destinations:
    // the party for a track someone else queued, the queue for a queue-built
    // session, and the page the session was started from for everything else. The
    // first of those is a family this build does not carry and the third is the
    // detail page (slice 4), whose parameter came off the table — so the caption
    // opens the queue when the session *is* one, and for every other origin it is
    // the same line of text it always was with nowhere to go. Task 11's adapter
    // decides whether the non-queue caption stays tappable; the album and artist
    // titles next to it are the other two entries waiting on the same page.
    val openPlaybackOrigin: () -> Unit = {
        if (song.playbackSourceType == PlaybackSourceType.QUEUE) {
            queueOpen = true
        }
    }

    // Horizontal fling skips tracks. The portrait player hangs it on the whole
    // screen, the landscape one on the sleeve alone — the right column there is
    // full of horizontal sliders and should not be one stray sideways drag away
    // from changing the song.
    val skipSwipeGesture = Modifier.pointerInput(panelScrolling) {
        if (panelScrolling) return@pointerInput
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragCancel = { setSwipeOffset(0f) },
            onDragEnd = {
                // The same two buzzes the transport glyphs give, so swiping the
                // sleeve and tapping skip feel like one gesture with two
                // spellings. Upstream had a third answer here — a party host can
                // take the controls from this device, and a swipe that crossed the
                // threshold said so instead of moving — which went with the
                // `controlsLocked` read and its `onBlockedControl` parameter.
                when {
                    total <= -swipeThreshold -> {
                        haptics.play(Haptic.SkipNext)
                        onNext()
                    }
                    total >= swipeThreshold -> {
                        haptics.play(Haptic.SkipPrevious)
                        onPrevious()
                    }
                }
                setSwipeOffset(0f)
            },
            onHorizontalDrag = { _, delta ->
                total += delta
                // Damped: it's a hint, not a drag-to-position.
                setSwipeOffset(total * 0.35f)
            },
        )
    }

    // The scrubber's two halves, shared by both layouts so a drop point is held
    // the same way in each — see [PlayerScrub.pendingSeek].
    val onScrub: (Float) -> Unit = scrub::drag
    val onScrubFinished: () -> Unit = {
        // On release only. Ticking the whole way along the bar turns a scrub
        // into a rattle, and the beat that matters is the one that says where
        // the playhead landed.
        haptics.play(Haptic.Select)
        scrub.release(onSeekFraction)
    }

    // Shuffle, repeat and the queue — the row both layouts end on, and the only
    // thing in the landscape player's left column besides the sleeve. Upstream's
    // version of this row also carried lyrics, AutoPlay and the output/party
    // capsule, and named the current output underneath it; all four are later
    // slices or do not exist here, and the caption that went with the output name
    // went with them.
    val playerActions: @Composable () -> Unit = {
        PlayerActionRow(
            queueOpen = queueOpen,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            onToggleQueue = toggleQueue,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat,
        )
    }

    // A landscape window — a tablet, or a phone on its side — takes an entirely
    // different shape from the portrait player below: two columns rather than
    // one, see [landscapePlayerAvailable] and [LandscapePlayerLayout].
    //
    // A separate branch rather than something woven into the layout below:
    // the portrait player's collapsing sleeve, hero banner and vertical drag
    // gesture exist to let one tall column be the player and the queue in turn,
    // and in landscape none of them has anything to do — the sleeve never has to
    // get out of anything's way.
    val landscape = landscapePlayerAvailable(windowWidth, windowHeight)
    // Tablet-sized players use the full-cover blur in all three states. On a
    // phone only the queue replaces the main player's seam-aware, reflected mesh;
    // the main player deliberately keeps its existing look.
    val tabletArtworkBackdrop = tabletSizedPlayer(windowWidth)
    // Owned by the song-level player composition, not by any one screen state.
    //
    // What that buys on this platform is not what upstream's note claimed. There
    // the point was that the cached copy was already a *pre-blurred* 128px bitmap,
    // so opening the queue or rotating never ran a screen-sized effect; here the
    // cached copy is the still, unblurred decode and [FullArtworkBlurBackdrop]
    // runs a screen-sized `Modifier.blur` over it every time it is drawn (Task 9
    // fixed that radius at 48.dp). What is saved by holding it at the song level,
    // then, is the decode and the cache hit — the blur runs whether or not this
    // line exists, and moves when the panel does.
    val fullArtworkBlurImage = rememberFullArtworkBlurImage(
        imageUrl = remoteArt,
        artPx = ART_PX,
        prepare = landscape || tabletArtworkBackdrop || queueOpen ||
            playerPrewarmStage >= 1,
    )
    // Upstream wrapped the backdrop in `movableContentOf`, keyed on a
    // `rememberUpdatedState` of the image: the portrait and landscape branches are
    // two different call sites, and Compose would have torn the first one's node
    // down and built the second's when the window crossed the landscape rule, so
    // the backdrop's own fade — and the decoded image held behind it — restarted
    // mid-rotation. Two call sites, one hoisted subtree.
    //
    // It is called directly twice here instead, because the state that made the
    // hoist pay — the layer's alpha tween, which lives inside
    // [FullArtworkBlurBackdrop] and would have been thrown away and re-run — is a
    // 320ms fade of a still image, not a live effect. If a rotation visibly
    // re-fades the backdrop at Task 13, the movable content is the first thing to
    // put back, and it goes back exactly as upstream had it.

    if (landscape) {
        // Paused always wins outright over a scrub in progress — see
        // [ARTWORK_PAUSE_SHRINK_SCALE].
        val landscapeArtScale by animateFloatAsState(
            targetValue = when {
                !isPlaying -> ARTWORK_PAUSE_SHRINK_SCALE
                scrub.scrubbing -> ARTWORK_DRAG_SHRINK_SCALE
                else -> ARTWORK_EXPANDED_SCALE
            },
            animationSpec = tween(durationMillis = ARTWORK_SCALE_DURATION_MS, easing = ArtworkScaleEasing),
            label = "landscapeArtworkScale",
        )

        Box(modifier = modifier.fillMaxSize()) {
            LandscapePlayerLayout(
                pane = if (queueOpen) PlayerPane.Queue else PlayerPane.Main,
                background = { backgroundModifier ->
                    // The whole screen is the sheets' frost source: there is
                    // no full-bleed banner here to be it instead.
                    FullArtworkBlurBackdrop(
                        image = fullArtworkBlurImage,
                        modifier = backgroundModifier.hazeSource(playerHaze),
                    )
                },
                artwork = { artworkModifier ->
                    LandscapeArtwork(
                        artRequest = art.request,
                        artLoaded = art.loaded,
                        onArtState = art::onState,
                        modifier = artworkModifier
                            .then(skipSwipeGesture)
                            .graphicsLayer {
                                scaleX = landscapeArtScale
                                scaleY = landscapeArtScale
                                translationX = swipeSettle.value
                            }
                            // With the queue up the sleeve is the way back to
                            // the player, as the portrait thumbnail is.
                            .clickable(
                                enabled = queueOpen,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                queueOpen = false
                            },
                    ) {
                        // The stats belong to the player, not to the sleeve, so
                        // they leave it along with the rest of the player.
                        SleeveNerdStats(
                            song = song,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .graphicsLayer { alpha = if (queueOpen) 0f else 1f },
                        )
                    }
                },
                actions = playerActions,
                mainPane = { compact ->
                    LandscapeMainPane(
                        compact = compact,
                        caption = playbackOriginText(song),
                        onOpenCaption = openPlaybackOrigin,
                        credits = {
                            LandscapeCredits(
                                song = song,
                                likeStatus = likeStatus,
                                onToggleLike = onToggleLike,
                                onOpenMenu = onOpenMenu,
                                onOpenAlbum = onOpenAlbum,
                                onOpenArtist = onOpenArtist,
                            )
                        },
                        scrubber = {
                            PlayerScrubber(
                                shown = shown,
                                durationMs = durationMs,
                                // Upstream also lit this for the length of an
                                // audio-version switch, and marked the smart-fade
                                // planner's window on the bar. Neither the switch
                                // nor the planner is here, so the bar is only ever
                                // as busy as the stream.
                                loading = isLoading,
                                transitionWindow = null,
                                onScrub = onScrub,
                                onScrubFinished = onScrubFinished,
                            ) {
                                PlaybackQualityLabel(
                                    song = song,
                                    isLoading = isLoading,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(horizontal = 8.dp),
                                )
                            }
                        },
                        transport = {
                            TransportRow(
                                isPlaying = isPlaying,
                                isLoading = isLoading,
                                previousEnabled = hasPrevious || pastRestartPoint,
                                nextEnabled = hasNext,
                                onPrevious = onPrevious,
                                onPlayPause = onPlayPause,
                                onNext = onNext,
                                compact = compact,
                            )
                        },
                        // The bar, where upstream renders it (app :1552-1562) — the
                        // column's last item, so its arrival pushes nothing else
                        // around. Upstream nulls this slot when Settings hides the
                        // bar; desktop has no such setting and no sheet to hold it,
                        // so the row is always handed over. See [onVolumeChange] for
                        // why finishing is empty here.
                        volume = {
                            VolumeRow(
                                value = { volume },
                                onValueChange = onVolumeChange,
                                onValueChangeFinished = { },
                            )
                        },
                    )
                },
                queuePane = {
                    InlineQueue(
                        queue = queue,
                        currentIndex = queueIndex,
                        autoplayEnabled = false,
                        onJumpTo = onJumpTo,
                        onRemove = onRemoveFromQueue,
                        onMove = onMoveInQueue,
                        onClear = onClearQueue,
                        onDragActiveChange = onQueueDragActiveChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        }
        return
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Anchored to the sleeve's bottom edge, so the screen carries on in the
        // colours the artwork ended in rather than in a quantiser's idea of what
        // the artwork was about. Position ticks recompose this screen twice a
        // second and must not drag a full-screen blur along with them, which is
        // why the mesh is passed as one immutable value.
        //
        // The seam is the *expanded* banner's bottom edge and is left there as
        // the player collapses, rather than following the sleeve down: it is
        // the anchor for a blurred layer, and moving it would re-blur the whole
        // screen on every frame of the drag. Above it the mesh holds one colour,
        // so a seam left behind a collapsed sleeve shows nothing at all.
        // Both phone backgrounds stay mounted for the entire song. Only these
        // retained layers' alpha changes when a panel opens, so the full-cover
        // blur is neither rebuilt nor switched in on a hard frame boundary.
        // The tablet has no mirrored main-player treatment to crossfade from.
        val fullArtworkBackdropAlpha by animateFloatAsState(
            targetValue = if (
                (tabletArtworkBackdrop || queueOpen) &&
                (tabletArtworkBackdrop || fullArtworkBlurImage != null)
            ) 1f else 0f,
            animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
            label = "fullArtworkBackdropCrossfade",
        )
        ArtworkMeshBackdrop(
            mesh = artMesh,
            seam = if (heroMode) heroHeight else 0.dp,
            modifier = Modifier.graphicsLayer { alpha = 1f - fullArtworkBackdropAlpha },
        )
        FullArtworkBlurBackdrop(
            image = fullArtworkBlurImage,
            modifier = Modifier.graphicsLayer { alpha = fullArtworkBackdropAlpha },
        )

        // The artwork, edge to edge and running up behind the status bar,
        // dissolving into the backdrop where the sleeve's bottom edge would
        // have been. It lives out here rather than in the sleeve because that
        // is the only way to escape the player's side gutter and its status-bar
        // inset — a banner that stops short of either reads as a misplaced card
        // rather than as the artwork the screen is made of.
        if (heroHeight > 0.dp) {
            // The still sleeve first, so a clip fading in on top of it never
            // shows the backdrop through the gap between them. There is no clip
            // here any more, but the second half of that reason stands: both this
            // and the card carry the same bottom gradient, so a banner pulled out
            // mid-collapse leaves the frame short of artwork, and the collapse is
            // what has to take it away rather than a threshold on the way down.
            //
            // Kept mounted through the handover in either direction rather than
            // dropped the moment [p] crosses the collapse threshold: the sleeve
            // behind it is still transparent at that point, so pulling the
            // banner straight out leaves a frame or two with no artwork anywhere
            // on screen before the card catches up.
            if (heroMode && (!collapsePastHalf || heroShowing)) {
                // Dropping this painter (while a panel is open) also drops the
                // proof that this particular destination can draw. If it is
                // mounted again, keep the sleeve visible until the new painter
                // reports Success.
                DisposableEffect(art.request) {
                    onDispose { heroArtLoaded = false }
                }
                AsyncImage(
                    // Decoded at the same size the sleeve asks for, so the two
                    // share one entry in Coil's cache and one bitmap: the pair
                    // cross-fade into each other, and asking twice at two sizes
                    // would decode the same art twice and let the banner fade in
                    // before its own copy had arrived.
                    //
                    // Literally the same request object as the sleeve's, not an
                    // identical one — see [PlayerArtwork.request] for why that
                    // distinction is the whole of it.
                    model = art.request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onState = { heroArtLoaded = it is AsyncImagePainter.State.Success },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(heroHeight)
                        // Haze must observe the drawable layer itself. A
                        // source on the surrounding layout only captured its
                        // mesh backdrop, leaving the cover sharp in the pill.
                        .hazeSource(playerHaze)
                        .graphicsLayer {
                            alpha = heroVisible()
                            // The mask below erases part of what this layer
                            // drew, which it can only do in a buffer of its own.
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Black, Color.Transparent),
                                    startY = size.height * (1f - HERO_FADE_FRACTION),
                                    endY = size.height,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
            }
        }

        // This transparent top gradient is always present while the modal
        // player owns the top of the window. Its opacity follows the actual
        // top-band artwork, rather than changing the glyph colour per cover —
        // upstream had glyphs to change. A subview replaces the hero with an
        // artwork-derived mesh, so it gets only a modest floor rather than an
        // opaque status-bar surface.
        val topGradientAlpha = if (queueOpen) {
            maxOf(artworkStatusScrimAlpha, SUBVIEW_STATUS_SCRIM_MIN_ALPHA)
        } else {
            artworkStatusScrimAlpha
        }

        val steps = 8
        val gradientColors = remember(topGradientAlpha) {
            List(steps) { index ->
                val progress = index / (steps - 1).toFloat()
                val factor = (1f - progress).toDouble().pow(1.5).toFloat()
                Color.Black.copy(alpha = topGradientAlpha * factor)
            }
        }
        val topScrimBrush = remember(gradientColors) {
            Brush.verticalGradient(gradientColors)
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(statusBarTop + topStrip)
                .background(topScrimBrush)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .then(skipSwipeGesture),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The only strip that passes drags through to the sheet, so the
            // player closes from the handle and the space around it — not from
            // a stray downward swipe on the artwork or the controls.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topStrip),
                contentAlignment = Alignment.Center,
            ) {
                // While the origin caption is present the handle belongs at
                // the top of the strip. As the queue replaces the album-cover
                // player, it glides into the now-empty strip's vertical centre
                // alongside the caption's fade.
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset {
                            IntOffset(
                                x = 0,
                                y = lerp(
                                    6.dp,
                                    (topStrip - 5.dp).coerceAtLeast(0.dp) / 2,
                                    p(),
                                ).roundToPx(),
                            )
                        }
                        .width(38.dp)
                        .height(5.dp)
                        .shadow(2.dp, RoundedCornerShape(3.dp), clip = false)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.70f)),
                )
                // [p] is the shared album-to-panel transition. Keeping this in
                // composition until its final frame gives the caption a real
                // fade on both entry and exit, but removes its click target
                // entirely once the queue owns the player.
                if (!collapseAlmostDone) {
                    PlaybackOriginCaption(
                        text = playbackOriginText(song),
                        onClick = openPlaybackOrigin,
                        textAlign = TextAlign.Center,
                        contentPadding = PaddingValues(start = PLAYER_GUTTER, end = PLAYER_GUTTER, bottom = 1.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .graphicsLayer { alpha = 1f - p() },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Swallow vertical drags before the sheet can read them as
                    // "dismiss me". Children that scroll consume first, so the
                    // lists are unaffected. This sits outside the side padding
                    // on purpose: inside it, the two gutters were left as bare
                    // sheet, and a swipe that strayed into one closed the whole
                    // player instead of scrolling the queue.
                    //
                    // With one hole in it, and where that hole is depends on
                    // which screen of the player is up:
                    //
                    //  * The main player — the artwork-and-credits block. Down is
                    //    left unconsumed for the sheet to dismiss with, so the
                    //    player closes from the picture as well as from the
                    //    handle; up is taken here and drags the queue in.
                    //  * The queue — the header it sits below, and nothing else.
                    //    Down closes the player, up does nothing: there is no
                    //    sleeve left to pull away from.
                    //
                    // The header is worked out from the state rather than read
                    // off the sleeve, which is the whole point of doing it here:
                    // the sleeve is still on its way for [QUEUE_TRAVEL_MS] after
                    // the queue opens, and a hole that waited for it spent that
                    // half second lying across a list the finger was already
                    // scrolling.
                    .onGloballyPositioned { dismissBandSpace = it }
                    .pointerInput(panelScrolling) {
                        if (panelScrolling) return@pointerInput
                        awaitEachGesture {
                            // Unconsumed on purpose, as the blanket version was:
                            // the collapsed sleeve's own clickable — the way back
                            // out of the queue — has taken the press by the time
                            // an ancestor sees it.
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val space = dismissBandSpace
                            val y = space?.localToRoot(down.position)?.y
                                ?: down.position.y
                            // A panel is up from the moment it is asked for to
                            // the moment the sleeve has finished growing back —
                            // never mind where the sleeve is in between.
                            val panelUp = queueOpen || queueSlide.floatValue > 0.01f
                            val bandTop: Float
                            val bandBottom: Float
                            if (panelUp) {
                                bandTop = space?.positionInRoot()?.y ?: 0f
                                bandBottom = bandTop +
                                    (ART_BOX_TOP_PAD + HEADER_HEIGHT).toPx()
                            } else {
                                bandTop = dismissBandTop
                                bandBottom = dismissBandBottom
                            }
                            if (y >= bandTop && y <= bandBottom) {
                                if (!panelUp) {
                                    dragQueueIn(
                                        down = down,
                                        travel = bandBottom - bandTop -
                                            HEADER_HEIGHT.toPx(),
                                        slide = queueSlide,
                                        onHold = { queueDragging = it },
                                        onSettle = { open ->
                                            if (open != queueOpen) {
                                                haptics.play(
                                                    if (open) Haptic.Expand else Haptic.Tap,
                                                )
                                                queueOpen = open
                                            }
                                            queueReleased++
                                        },
                                    )
                                }
                                return@awaitEachGesture
                            }
                            // A down already taken means the sheet grabbed it
                            // while "settling" after a scroll, and the guard is
                            // about to hand it back to the list under the
                            // finger. Holding it here would strand that list
                            // mid-handback, and the list would never scroll.
                            if (down.isConsumed) return@awaitEachGesture
                            // What detectVerticalDragGestures does, minus the
                            // callbacks: cross the slop, then hold the gesture
                            // to the end so nothing downstream of the first
                            // event reaches the sheet either.
                            val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                            }
                            if (drag != null) verticalDrag(drag.id) { it.consume() }
                        }
                    }
                    .padding(horizontal = PLAYER_GUTTER),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            // ---- Top and centre: artwork, then the credits ----
            // Everything that changes between the artwork and the queue lives
            // in this one weighted box, so the controls below it never move.
            // The loading state the scrubber wears for the length of a version
            // switch is gone with the switch, so the flag below is just the
            // stream's own.
            // Height the artwork block below turns out not to need, spent by the
            // controls at the foot of the screen. Filled in from inside the box,
            // where the sleeve's real size is known; see [lastControlSpread].
            var controlSpread by remember { mutableStateOf(lastControlSpread) }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = PLAYER_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(top = ART_BOX_TOP_PAD, bottom = 18.dp),
            ) {
                // The height this box would have if the controls at the foot of
                // the screen were at their natural size. They aren't: they are
                // holding [controlSpread] of extra gap, which came out of here,
                // so adding it back cancels the only thing down there that
                // depends on what is decided up here.
                //
                // The sleeve and the slack below are both worked out from this
                // rather than from the box as it actually stands, and that is
                // what keeps the hand-off from creeping. Measured off the real
                // height, granting the gaps 20dp came back as a box 20dp
                // shorter and read as a *further* 20dp going spare — so any
                // moment the controls were briefly shorter than usual (a track
                // change, or coming back from the queue) was pocketed for good.
                // The gaps ratcheted open a little at a time and the sleeve paid
                // for it.
                val roomy = maxHeight + controlSpread
                // The sleeve is square, so it is bounded by whichever of the
                // two axes runs out first: the player's width on a phone, or —
                // on a tablet, where there is width to spare — the height left
                // over once the credits row and the gap above it have had
                // theirs. Sizing it off the width alone is what pushed the
                // credits down across the scrubber on anything but a phone.
                val wantArt = minOf(maxWidth, roomy - ART_TITLE_GAP - HEADER_HEIGHT)
                // Held to what the box has actually got, for the single frame it
                // takes the gaps below to catch up with a change in their own
                // height: a sleeve a few dp under for one frame is a better
                // failure than a credits row overhanging the controls.
                val fullArt = minOf(wantArt, maxHeight - ART_TITLE_GAP - HEADER_HEIGHT)
                    .coerceAtLeast(THUMB_SIZE)
                // What's left over once the sleeve, the gap and the credits have
                // had theirs. A few dp on a phone; the better part of a
                // centimetre on anything taller, and since the group is centred,
                // half of it used to land between the credits and the strip below
                // it as one wide hole in the middle of the controls.
                val slack = (roomy - wantArt - ART_TITLE_GAP - HEADER_HEIGHT)
                    .coerceAtLeast(0.dp)
                // Handed to the two gaps around the transport row instead, which
                // is where a tall screen should be doing its breathing.
                //
                // Assigned, not added to: [slack] is stated in terms the spread
                // cannot move, so this is the whole answer in one step, and it
                // gives the room back just as readily when the controls grow
                // into it again.
                //
                // The settled spread is retained while the panel is up. It is
                // part of the controls' footprint, not part of the artwork, and
                // removing it only for the queue made the half-player jump
                // shorter at the exact moment the sleeve started collapsing.
                //
                // Granted in whole even pixels, and only when it actually moves.
                // This is a measurement feeding the layout it was measured from,
                // and [roomy] cancels that by adding the grant back — but only if
                // this pass's [maxHeight] already reflects the grant about to be
                // written, which needs the Column above to have re-measured the
                // controls at that grant already. It doesn't always have: on some
                // aspect ratios the cancellation lands a pass late, the grant
                // overshoots, the next pass corrects past it the other way, and
                // the two chase each other through the same handful of values
                // forever instead of settling — a full-amplitude standing
                // oscillation, not the single-pixel shiver this rounding alone
                // was built to absorb. See [granted] below for the fix.
                if (collapseAtRest) {
                    val target = with(density) {
                        val half = slack
                            .coerceAtMost(CONTROL_GAP_SPREAD_MAX * 2)
                            .toPx()
                            .div(2f)
                            .roundToInt()
                        (half * 2).toDp()
                    }
                    // Stepped towards [target] rather than jumped there in one
                    // grant, so a late cancellation (see above) decays instead of
                    // standing: still one pass to settle when the cancellation
                    // does land on time, and a fast-converging approach rather
                    // than a full-amplitude swing on the passes where it doesn't.
                    val granted = with(density) {
                        val steppedPx = (controlSpread.toPx() +
                            (target.toPx() - controlSpread.toPx()) * 0.4f)
                            .roundToInt()
                        steppedPx.toDp()
                    }
                    if (granted != controlSpread) {
                        SideEffect {
                            controlSpread = granted
                            lastControlSpread = granted
                        }
                    }
                }
                // Artwork and the title row travel together as one block, so
                // the pair sits centred while the queue is closed — in whatever
                // the controls couldn't take, which on all but the tallest
                // screens is nothing.
                val groupTop = (maxHeight - fullArt - ART_TITLE_GAP - HEADER_HEIGHT)
                    .coerceAtLeast(0.dp) / 2
                // Functions of the collapse, called at placement and measure
                // — see [p]. Composition never reads them.
                fun artSize(): Dp = lerp(fullArt, THUMB_SIZE, p())
                fun artTop(): Dp = lerp(groupTop, 0.dp, p())
                // Expanded and height-bound, the sleeve is narrower than the
                // player and has to be centred in it; collapsed, it belongs
                // hard against the left edge with the credits beside it.
                fun artStart(): Dp = lerp((maxWidth - fullArt) / 2, 0.dp, p())
                fun regularTitleTop(): Dp = lerp(groupTop + fullArt + ART_TITLE_GAP, 0.dp, p())
                fun titleStart(): Dp = lerp(0.dp, THUMB_SIZE + 12.dp, p())

                // How far down the *screen* the sleeve's bottom edge sits, which
                // is where the full-bleed banner has to stop for the credits
                // below it not to move when it appears. Everything between the
                // screen's top and this box's own top is fixed padding, so it
                // can simply be added back up rather than measured.
                val bannerBottom = statusBarTop + topStrip + ART_BOX_TOP_PAD +
                    groupTop + fullArt + ART_TITLE_GAP / 2
                // Upstream froze this while the lyric sheet was up, because that
                // panel could open over an unmeasured sleeve. With the sheet gone
                // the guard was always true, so the write is guarded on its own
                // value alone: this runs on every pass, and a state write from
                // inside a layout is a recomposition asked for from inside a
                // layout. Writing the same answer back costs a comparison here
                // and a whole frame if it is left to the snapshot to notice.
                if (bannerBottom != heroHeight) {
                    SideEffect { heroHeight = bannerBottom }
                }

                // Empty state lives on this Box, not the AsyncImage: a
                // background *and* a painter both trying to fill the same
                // clipped shape is what read as two overlapping squares
                // whenever there was nothing to paint. One layer, one square.
                // [PlayerArtwork.loaded] is hoisted to the screen, where the banner
                // needs it too.
                Box(
                    modifier = Modifier
                        // The lambda overload deliberately: the Dp one reads
                        // its arguments at composition, so an animated offset
                        // recomposes and re-measures this Box — cover, clip and
                        // all — once per frame. Read at placement instead, the
                        // same movement costs a placement pass.
                        .offset { IntOffset(artStart().roundToPx(), artTop().roundToPx()) }
                        // What `.size(artSize)` measured, asked at measure
                        // time instead of composition.
                        .layout { measurable, constraints ->
                            val side = artSize().roundToPx()
                            val placeable = measurable.measure(
                                constraints.constrain(Constraints.fixed(side, side)),
                            )
                            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                        }
                        // Where the dismiss band starts. Read here, above the
                        // paused shrink below, so the band covers the sleeve's
                        // slot rather than the 86% of it that is drawn while
                        // paused — the ring of backdrop the shrink opens up is
                        // still the artwork as far as a finger is concerned, and
                        // a band that breathed with the shrink would hand it
                        // back and forth on every play and pause.
                        .onGloballyPositioned { dismissBandTop = it.boundsInRoot().top }
                        .graphicsLayer {
                            // The paused shrink and the swipe nudge only make
                            // sense on the full sleeve.
                            val collapse = p()
                            val idle = artScale + (1f - artScale) * collapse
                            scaleX = idle
                            scaleY = idle
                            translationX = swipeSettle.value * (1f - collapse)
                        }
                        // Collapsed, the sleeve is the way back: tapping the
                        // thumbnail puts the queue away again.
                        .then(
                            if (queueOpen) {
                                Modifier.clickable { queueOpen = false }
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // The sleeve proper. Separated from the box around it so
                    // the banner can dissolve the card — shadow, corners, tile
                    // and all — without taking the stats line with it.
                    //
                    // Held fully opaque until the destination banner has artwork
                    // of its own: the banner is sticky across skips by design (see
                    // [heroSettled]), but its content is not — a new track's cover
                    // has to come from somewhere while the banner waits on Coil,
                    // and the sleeve underneath, with its loading icon, is that
                    // somewhere. Once the destination catches up, hiding the
                    // sleeve behind the banner is invisible. [PlayerArtwork.loaded]
                    // alone is not enough: the two use separate painters, and the
                    // banner can still be empty for a frame after the sleeve
                    // reports Success.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // The compact sleeve is the frost source while full
                            // bleed artwork is off.
                            .hazeSource(playerHaze)
                            .graphicsLayer {
                                alpha = if (heroArtLoaded) 1f - heroVisible() else 1f
                            }
                            // A drop shadow grounds a photo; on the flat
                            // placeholder tile it has nothing to sit behind, so
                            // it just reads as a second, darker square ringing
                            // the first. Only cast it once there's actually art.
                            .shadow(
                                if (art.loaded) 10.dp else 0.dp,
                                RoundedCornerShape(8.dp),
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!art.loaded) {
                            Icon(
                                imageVector = BitChordIcons.MusicNote,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .size(40.dp)
                                    .graphicsLayer {
                                        val scale = 1f - 0.5f * p()
                                        scaleX = scale
                                        scaleY = scale
                                    },
                            )
                        }
                        AsyncImage(
                            // Decode at the sleeve's *expanded* size, always.
                            // Coil otherwise sizes the decode to however large
                            // this is when the request goes out — and changing
                            // track from the queue does that while the sleeve is
                            // collapsed to a thumbnail, leaving a thumbnail-sized
                            // bitmap to be blown back up when the queue closes.
                            // Skipping tracks with the transport keeps it sharp
                            // only because the sleeve happens to be full size at
                            // that moment.
                            //
                            // Asked for at the source's own size rather than the
                            // sleeve's: it is the same request the full-bleed
                            // banner makes, and the banner is taller than the
                            // sleeve is wide. One ask, one decode, one bitmap for
                            // both — and nothing to upscale when the two swap.
                            model = art.request,
                            contentDescription = null,
                            // Video thumbnails are 16:9; letterboxing them inside
                            // the square sleeve looks like a broken frame.
                            contentScale = ContentScale.Crop,
                            onState = art::onState,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // Measured stats stay on the sleeve's bottom centre.
                    if (!collapsePastHalf) {
                        SleeveNerdStats(
                            song = song,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .graphicsLayer { alpha = 1f - p() * 2f },
                        )
                    }
                }

                // Sits in the gap under the sleeve, clear of its rounded
                // corners and shadow — no box, no clip, nothing for the art
                // itself to be cropped by. Just a glyph that fades in with
                // the drag to hint which way a release would skip.
                //
                // Shown under the banner as well as under the card, and it is
                // the only feedback the drag has there: a card can slide with
                // the finger, but a full-bleed image sliding would open a strip
                // of bare backdrop down one edge of the screen. It lands where
                // the banner has all but dissolved, so it reads against the
                // backdrop rather than against the artwork.
                if (swipeHintShown) {
                    val showNext = swipeHintNext
                    val enabled = if (showNext) hasNext else hasPrevious
                    val hintAlpha = if (enabled) 0.85f else 0.3f
                    Icon(
                        imageVector = if (showNext) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset {
                                IntOffset(0, (artTop() + artSize() + (ART_TITLE_GAP - 16.dp) / 2).roundToPx())
                            }
                            .size(16.dp)
                            .graphicsLayer {
                                alpha = (abs(swipeSettle.value) / swipeThreshold)
                                    .coerceIn(0f, 1f) * (1f - p()) * hintAlpha
                            },
                    )
                }

                // ---- Title + menu ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Collapsed, this row shares the header with the sleeve
                        // rather than sitting under it, and the two are not the
                        // same height — centring the credits in the taller of
                        // the two boxes left them riding low against the
                        // artwork they belong to. Only as it collapses: opened
                        // out, the row is below the sleeve and owns its band.
                        // Read the animated value during placement. The Dp
                        // overload would recompose and remeasure this whole
                        // weighted player region on every animation frame.
                        .offset {
                            IntOffset(
                                x = 0,
                                y = (
                                    regularTitleTop() -
                                        lerp(0.dp, (HEADER_HEIGHT - THUMB_SIZE) / 2, p())
                                    ).roundToPx(),
                            )
                        }
                        // What `.padding(start = titleStart)` laid out, asked at
                        // measure time instead of composition.
                        .layout { measurable, constraints ->
                            val start = titleStart().roundToPx()
                            val placeable = measurable.measure(constraints.offset(horizontal = -start))
                            layout(
                                constraints.constrainWidth(placeable.width + start),
                                constraints.constrainHeight(placeable.height),
                            ) { placeable.placeRelative(start, 0) }
                        }
                        .height(HEADER_HEIGHT)
                        // Where the dismiss band ends — see its top on the
                        // artwork above. Taken from the row rather than added up
                        // from the sleeve so the gap between the two is inside
                        // the band as well: it is a gap in one block, not a seam
                        // between two, and a finger should not be able to find it.
                        .onGloballyPositioned { dismissBandBottom = it.boundsInRoot().bottom },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            // Scaling the already measured type is a draw-only
                            // operation. Animating fontSize forced both marquee
                            // texts through shaping and measurement every frame.
                            .graphicsLayer {
                                val scale = 1f - 0.2f * p()
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            },
                    ) {
                        // Only the title's own overflow gates the artist's stagger
                        // below — an artist line that's long on its own has no
                        // reason to wait on a title that already fits.
                        var titleOverflowing by remember { mutableStateOf(false) }
                        // Only while these credits are the screen. Collapsed into
                        // a header over the queue they are a label on a list, and
                        // a label that crawls pulls the eye off whatever is being
                        // read below it.
                        val scrolls = !collapsePastSettling
                        // Targeted on the words rather than the videoId: two
                        // cuts that share a title cross-fade into the exact
                        // same text, which is nothing at all and leaves the
                        // marquee where it was; a cut that renames the song
                        // ("… (Live)") dissolves into the new one instead of
                        // snapping while the rest of the switch moves around
                        // it.
                        Crossfade(
                            targetState = song.title to song.artist,
                            animationSpec = tween(durationMillis = 300),
                            label = "playerCredits",
                        ) {
                            Column {
                                MarqueeText(
                                    text = song.title,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontSize = 20.sp,
                                    ),
                                    color = Color.White,
                                    enabled = scrolls,
                                    leading = if (song.isExplicit == true) {
                                        { ExplicitBadge(color = Color.White) }
                                    } else {
                                        null
                                    },
                                    onOverflowChange = { titleOverflowing = it },
                                    // Only the tracks YouTube hands us a browse id for
                                    // lead anywhere; the rest stay plain text.
                                    modifier = Modifier.opensPage(song.albumId, onOpenAlbum),
                                )
                                MarqueeText(
                                    text = song.artist,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.W500,
                                        fontSize = 20.sp,
                                    ),
                                    color = Color.White.copy(alpha = 0.55f),
                                    enabled = scrolls,
                                    // A title that's also scrolling gets to go first —
                                    // starting together reads as clutter, so the artist
                                    // waits a beat before it joins in.
                                    startDelayMillis = if (titleOverflowing) MARQUEE_ARTIST_STAGGER_MS else 0L,
                                    modifier = Modifier.opensPage(song.artistId, onOpenArtist),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    // Beside the credits rather than down in the toggle row:
                    // liking is about *this song*, and the row below is about
                    // how the queue plays. Upstream also hid this from a guest,
                    // with no account to record the tap against; signing in is
                    // a later slice and this build has no account state to ask
                    // for, so what survives is upstream's other gate — a local
                    // file carries no YouTube identity to rate.
                    if (song.localUri == null) {
                        val liked = likeStatus == LikeStatus.LIKE
                        CircleGlyph(
                            icon = if (liked) BitChordIcons.HeartFilled else BitChordIcons.Heart,
                            contentDescription = stringResource(
                                if (liked) Res.string.remove_from_liked else Res.string.like,
                            ),
                            onClick = onToggleLike,
                            active = liked,
                            haptic = if (liked) Haptic.ToggleOff else Haptic.ToggleOn,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    CircleGlyph(
                        icon = Icons.Rounded.MoreHoriz,
                        contentDescription = stringResource(Res.string.more),
                        onClick = onOpenMenu,
                    )
                }

                // Toggles and the queue arrive after the sleeve has finished
                // travelling, and leave before it starts coming back.
                // Held back until the sleeve has settled, except while a finger
                // is actually dragging the queue in. A drag is direct
                // manipulation: the queue has to be under the finger the whole
                // way for the gesture to mean anything, and the person doing it
                // is setting the pace, so there is no animation of ours for the
                // composition to trip up.
                val queuePanelVisible =
                    queueDragging || (queueShowing && panelsSettled)
                if (queuePanelVisible || playerPrewarmStage >= 2) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = HEADER_HEIGHT)
                            .offset {
                                if (queuePanelVisible) IntOffset.Zero else IntOffset(100_000, 0)
                            }
                            .graphicsLayer {
                                alpha = if (!queuePanelVisible) {
                                    0f
                                } else if (queueDragging) {
                                    ((queueSlide.floatValue - 0.45f) / 0.55f).coerceIn(0f, 1f)
                                } else {
                                    panelFade
                                }
                                translationY = if (queuePanelVisible) {
                                    (1f - queueSlide.floatValue) * 26.dp.toPx()
                                } else {
                                    0f
                                }
                            },
                    ) {
                        InlineQueue(
                            queue = queue,
                            currentIndex = queueIndex,
                            // Decision 6: the AUTOPLAY switch does not appear, and
                            // nothing fills the tier, so the section heading stays
                            // exactly where upstream's own emptiness test leaves it.
                            autoplayEnabled = false,
                            onJumpTo = onJumpTo,
                            onRemove = onRemoveFromQueue,
                            onMove = onMoveInQueue,
                            onClear = onClearQueue,
                            onScrollingChange = { queueScrolling = it },
                            onDragActiveChange = onQueueDragActiveChange,
                            // Upstream let a queue scroll slide the whole lower
                            // half-player — scrubber, transport, volume bar, toggle
                            // row — out of the way. That was live behaviour, not
                            // decoration: app `:2811-2813` wrote `queueControlsOpen`
                            // from the queue's own scroll, app `:2825-2827` gated
                            // `SlidingPlayerDeck` on it, and app `:452-499` collapsed
                            // the block's measured height as it slid, so the panel
                            // took the space back. **The deck is not ported, and so
                            // that behaviour is lost here: scrolling the queue leaves
                            // the controls where they are.** Deleting it is still the
                            // right call for a desktop overlay — the deck is the app's
                            // bottom-reveal machinery, and decision 4 replaces what it
                            // revealed with Task 11's overlay and Esc — but it is a
                            // known deviation and is carried to Task 13, not a no-op.
                            // [InlineQueue]'s side of the plumbing
                            // (`collapsePlayerOnScroll`, `onRevealPlayer`,
                            // `onHidePlayer`) is still there at its defaults with
                            // nothing behind it, which is the other half of what Task
                            // 13 decides.
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ---- Bottom: scrubber, transport, toggles ----
            // One block, measured at its natural height and pinned to the foot
            // of the player. Whatever is left over above it is the artwork's,
            // which is what keeps this row of controls in the same place on
            // every screen instead of being shoved off the bottom of a tall one.
            Column(
                modifier = Modifier
                    .widthIn(max = PLAYER_MAX_WIDTH)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            PlayerScrubber(
                shown = shown,
                durationMs = durationMs,
                // Upstream wore the loading state for the length of an audio-version
                // switch as well — see the landscape call site above.
                loading = isLoading,
                // Hidden while scrubbing upstream: the smart-fade planner is still
                // describing where the transition *would* be, and a marker sitting
                // under a finger that is moving the playhead invites reading it as
                // a drag target. There is no planner on this platform, so there is
                // never a window to hide — the parameter stays, because
                // [PlayerScrubber] is shared with the landscape column and the
                // planner is a later slice.
                transitionWindow = null,
                onScrub = onScrub,
                onScrubFinished = onScrubFinished,
            ) {
                PlaybackQualityLabel(
                    song = song,
                    isLoading = isLoading,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 8.dp),
                )
            }

            // The transport rides midway between the two blocks it separates: the
            // scrubber above it, and the volume bar and toggle row below, which
            // sit close enough together to read as one. Both of its own gaps take
            // half the spread, so on a tall screen it holds the centre rather than
            // drifting up under the seek bar.
            Spacer(Modifier.height(8.dp + controlSpread / 2))

            TransportRow(
                isPlaying = isPlaying,
                isLoading = isLoading,
                previousEnabled = hasPrevious || pastRestartPoint,
                nextEnabled = hasNext,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
            )

            // The volume bar, where upstream renders it (app :2943-2953: the gap
            // spacer, then the bar in the visible half of its `if`, then a
            // `VOLUME_ROW_HEIGHT` spacer in the hidden half). The two branches are
            // the same height by construction — 32dp is ThinSlider's touch target,
            // 10dp of track under 22dp of padding — so the bar filling the slot
            // moves nothing below it. That switch rides `AppSettings.hideVolumeBar`,
            // a display preference with no settings sheet here and no field on
            // desktop's `AppSettings`, so there is no branch to keep: the bar is
            // simply always present. [VOLUME_ROW_HEIGHT] is left without a caller by
            // that — its only use upstream was this slot's empty state — and it is a
            // Task 7 surface, so it stays. See the task-10 report.
            Spacer(Modifier.height(12.dp + controlSpread / 2))

            VolumeRow(
                value = { volume },
                onValueChange = onVolumeChange,
                // Nothing to commit on release: see [onVolumeChange].
                onValueChangeFinished = { },
            )

            // The volume slider already has 13dp below its drawn track.
            // Balance that invisible inset with the caption gap below the icons.
            Spacer(Modifier.height(6.dp))

            playerActions()
            Spacer(Modifier.height(18.dp))
            }
            }
        }
    }
}

/**
 * The upward half of the sleeve's vertical gesture: dragged up, the artwork
 * block pulls the queue in behind it, following the finger the whole way and
 * settling to whichever end it was nearer on release.
 *
 * Downward is deliberately not ours. The sheet the player sits in is what closes
 * when the sleeve is dragged that way, and it can only read a drag it was
 * allowed to see — so a downward crossing of the touch slop is left entirely
 * alone and this returns having consumed nothing at all. On this platform Task
 * 11's overlay is that sheet, and it closes on Esc as well.
 *
 * Which of the two it is can only be known at the crossing, which is why the
 * decision is made there rather than at the press. A pointer event reaches a
 * child before its parent, so consuming the very event that crossed the slop is
 * enough to keep the sheet out of an upward drag, and letting that one event
 * through is enough to hand it a downward one — the sheet's own slop detector
 * gives up the moment it sees a change already spoken for.
 *
 * @param travel how far the sleeve has to be dragged for the queue to arrive.
 * @param slide the 0..1 the player's whole layout reads off.
 * @param onHold true while the finger owns [slide] and false when it hands it
 *   back; the settling animation is parked in between so the two never write the
 *   same value on alternate frames.
 * @param onSettle the state the release decided on, which that animation then
 *   finishes reaching from wherever the finger left off.
 */
private suspend fun AwaitPointerEventScope.dragQueueIn(
    down: PointerInputChange,
    travel: Float,
    slide: MutableFloatState,
    onHold: (Boolean) -> Unit,
    onSettle: (Boolean) -> Unit,
) {
    // A block with nowhere to travel — a player not yet measured — would divide
    // by nothing and snap the queue open on the first pixel of movement.
    if (travel < 1f) return

    var pulled = 0f
    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
        if (overSlop < 0f) {
            pulled = -overSlop
            change.consume()
        }
    }
    if (drag == null || pulled <= 0f) return

    onHold(true)
    val velocity = VelocityTracker()
    velocity.addPointerInputChange(drag)
    slide.floatValue = (pulled / travel).coerceIn(0f, 1f)
    verticalDrag(drag.id) { change ->
        velocity.addPointerInputChange(change)
        pulled -= change.positionChange().y
        slide.floatValue = (pulled / travel).coerceIn(0f, 1f)
        change.consume()
    }

    // A flick decides on its own — it says "open" without asking the finger to
    // travel at all. Anything slower goes to whichever end it got nearer to.
    val flick = -velocity.calculateVelocity().y
    val open = when {
        flick >= QUEUE_FLICK_VELOCITY -> true
        flick <= -QUEUE_FLICK_VELOCITY -> false
        else -> slide.floatValue >= QUEUE_CARRY_FRACTION
    }
    onHold(false)
    onSettle(open)
}

/**
 * The player's cover: the one request every surface draws it from, and whether it
 * has loaded.
 *
 * app `ui/player/PlayerState.kt:97-196`, which no other task carried; it comes
 * with the screen that owns the only two painters of it.
 *
 * [request] is the whole reason this is a class rather than a call at each site:
 * everything here is keyed on the cover's [url], and the sleeve and the banner
 * share this one request so Coil hands them one bitmap.
 */
@Stable
private class PlayerArtwork(val url: String?) {
    /**
     * Whether the cover is on screen. Keyed on the artwork rather than on the
     * track, because that is what it actually describes and because only Coil
     * can set it back to true.
     */
    var loaded by mutableStateOf(false)
        private set

    /** Latched on an error, and only upwards — cleared by the retry itself. */
    var failed by mutableStateOf(false)
        private set

    /** Which go at this cover we are on, and the reason there is more than one. */
    var attempt by mutableIntStateOf(0)
        private set

    /**
     * The one request for this cover, built once.
     *
     * Both the sleeve and the full-bleed banner draw from it, which is what
     * their own comments claim ("one ask, one decode, one bitmap for both") and
     * what building it inline at each of them quietly failed to deliver: Coil
     * compares models to decide whether to start a new load, and two separately
     * built requests are never equal — `ImageRequest` has no `equals`, and
     * neither does the size resolver `.size()` hands it. So each was its own
     * load, and worse, *every recomposition* was another one. The player
     * recomposes at least twice a second off the position tick, and each pass
     * pushed the painter back through Loading before it settled on Success
     * again, which is exactly the [loaded] this screen hangs the banner, the
     * sleeve's alpha, its shadow and its placeholder icon on.
     *
     * Remembered on the cover and the attempt, so it changes when the picture
     * changes and when a retry is deliberately asked for, and at no other time.
     *
     * The one desktop difference from upstream is the builder's argument: a
     * `Context` there, `PlatformContext.INSTANCE` here — the same substitution
     * `MeshGradient.kt:225` and `ArtworkPalette.kt:154` already make.
     */
    val request: ImageRequest by derivedStateOf {
        val attempt = attempt
        ImageRequest.Builder(PlatformContext.INSTANCE)
            .data(url)
            .size(ART_PX)
            // What makes a retry a new request as far as Coil's model comparison
            // is concerned. Only from the second go onwards, so the ordinary
            // request stays byte-identical to the one the mesh and the palette
            // make of the same cover and goes on sharing their memory-cache
            // entry. The disk key is unaffected either way.
            .apply { if (attempt > 0) memoryCacheKeyExtra("attempt", attempt.toString()) }
            .build()
    }

    /** Fed from each painter drawing [request]. */
    fun onState(state: AsyncImagePainter.State) {
        loaded = state is AsyncImagePainter.State.Success
        // Only the failure is latched, and only upwards: the retry that clears
        // it is [failed]'s own effect, and clearing it from a Loading state here
        // would cancel that effect's wait every time the painter passed back
        // through Loading.
        if (state is AsyncImagePainter.State.Success) failed = false
        if (state is AsyncImagePainter.State.Error) failed = true
    }

    fun retry() {
        failed = false
        attempt++
    }
}

/**
 * The cover, with its one request and its bounded retries.
 *
 * app `ui/player/PlayerState.kt:182-196`.
 */
@Composable
private fun rememberPlayerArtwork(remoteArt: String?): PlayerArtwork {
    val artUrl = remoteArt?.artworkAt(ART_PX)
    val art = remember(artUrl) { PlayerArtwork(artUrl) }
    LaunchedEffect(artUrl, art.failed) {
        // A track with no artwork at all fails immediately and would fail
        // identically three more times: there is no request to make, so there is
        // nothing a second go could do differently.
        if (artUrl == null || !art.failed || art.attempt >= ART_RETRIES) return@LaunchedEffect
        delay(ART_RETRY_DELAY_MS)
        art.retry()
    }
    return art
}

/**
 * The seek bar's own state: a drag in progress, and where the handle was
 * dropped until the player's position catches up with it.
 *
 * app `ui/player/PlayerState.kt:198-277`, verbatim apart from the settle window
 * constants, which the constants layer had left out for a reason that does not
 * hold — see [SEEK_SETTLE_TOLERANCE_MS].
 */
@Stable
private class PlayerScrub {
    var scrubbing by mutableStateOf(false)
        private set
    var value by mutableFloatStateOf(0f)
        private set

    /**
     * After releasing the scrubber the player needs to buffer before it
     * reports the new position. Kept showing where the user dropped it so the
     * handle doesn't snap back and then jump forward once loading finishes.
     */
    internal var pendingSeek by mutableStateOf<Float?>(null)

    /** Where the handle is drawn. */
    fun shown(positionMs: Long, durationMs: Long): Float {
        val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        return when {
            scrubbing -> value
            pendingSeek != null -> pendingSeek!!
            else -> fraction.coerceIn(0f, 1f)
        }
    }

    fun drag(to: Float) {
        scrubbing = true
        value = to
    }

    fun release(seekTo: (Float) -> Unit) {
        pendingSeek = value
        seekTo(value)
        scrubbing = false
    }
}

@Composable
private fun rememberPlayerScrub(
    trackId: String,
    position: PlaybackPosition,
    durationMs: Long,
): PlayerScrub {
    val scrub = remember { PlayerScrub() }
    var pendingSeek by scrub::pendingSeek

    // Released as soon as the player's own position agrees with where the handle
    // was dropped — and unconditionally a few seconds later whether it agrees or
    // not.
    //
    // The agreement test alone is not enough, because it is the only thing that
    // ever cleared the override: if the position never passes close to the
    // target — a clamped or rejected seek, a rendition swapped underneath, a
    // progress sample that steps straight over the window — nothing releases it
    // and the handle sits frozen at the drop point for the rest of the track.
    // Audio and the queue follow the real position perfectly throughout, so the
    // failure looks like a stuck seek bar on a track that is playing fine.
    //
    // Tolerance is absolute rather than a share of the duration: two percent is
    // a quarter-second on a jingle and twelve seconds on a long mix, and it is
    // the wall-clock gap that decides whether the handle appears to jump.
    //
    // Watched from inside the effect rather than keyed on the position, so the
    // screen that owns this does not have to read the playhead to drive it.
    LaunchedEffect(durationMs, pendingSeek) {
        val target = pendingSeek ?: return@LaunchedEffect
        if (durationMs <= 0) return@LaunchedEffect
        snapshotFlow { position.positionMs }.first { positionMs ->
            abs(positionMs - (target * durationMs).toLong()) < SEEK_SETTLE_TOLERANCE_MS
        }
        pendingSeek = null
    }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek == null) return@LaunchedEffect
        delay(SEEK_SETTLE_TIMEOUT_MS)
        pendingSeek = null
    }
    LaunchedEffect(trackId) { pendingSeek = null }

    return scrub
}
