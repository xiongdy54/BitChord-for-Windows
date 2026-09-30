// Constants and geometry only. The `NowPlayingScreen` composable body — and the
// private helpers that read these numbers — land in Task 10, which APPENDS to
// this file rather than copying the app's file over it. See the marker at the
// bottom before overwriting anything here.
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

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.PLAYER_ART_PX

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
