package com.music.bitchord.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import uk.co.caprica.vlcj.player.component.InputEvents
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import kotlin.math.ceil

private const val TAG = "CanvasArtworkPlayer"

/**
 * How long a clip gets to paint itself before the still art is brought in
 * behind it instead. Long enough to cover a decoder being re-created from
 * cold, short enough that a clip which is never coming back does not sit
 * there as a hole for the length of a glance.
 */
private const val REPAINT_TIMEOUT_MS = 700L

/**
 * Big enough that averaging it is stable, small enough that reading it back
 * is not an event. Every consumer reduces this to a handful of colours.
 */
private const val FRAME_CAPTURE_PX = 128

/**
 * The display upload is capped at this on its long edge. A canvas clip is
 * 640–1080 px and the hero draws it at window width, so this only ever bites
 * on the largest sources, and the sampler scales the result the rest of the
 * way — a bound on the per-frame conversion and upload, not a quality choice.
 * If a real machine still drops frames here, the next step is downsampling
 * straight to the hero's own pixel height, which the dst rect already knows.
 */
private const val DISPLAY_MAX_EDGE = 1280

/** How a clip fills the bounds supplied by its caller. */
enum class CanvasContentMode {
    CROP,
    FIT_PORTRAIT,
}

/**
 * One decoded frame off the playing clip, as plain ARGB ints.
 *
 * vlcj's callback video surface hands frames over in libvlc's `RV32` layout —
 * B, G, R, A bytes in memory — and the JVM-side buffers vlcj allocates for the
 * surface are set to the platform's native byte order, so read back as ints
 * they are already Java's `0xAARRGGBB`. Nothing in this file swaps channels;
 * the same fact is what lets vlcj's own default renderer point libvlc at a
 * `TYPE_INT_ARGB` image's backing array directly.
 */
internal class CanvasFrame(val pixels: IntArray, val width: Int, val height: Int)

/**
 * The looping video that plays over a track's cover art, sized to fill and
 * clipped by whatever laid it out.
 *
 * A second, deliberately unassuming media player, with its own
 * `MediaPlayerFactory` — the structural twin of upstream's second, silent
 * ExoPlayer: separate from the audio engine so the two never share instance
 * state, silent (the factory runs `--no-audio`, so a clip's soundtrack is
 * never even routed to an output), and looping on one media
 * (`controls().setRepeat(true)`, upstream's `REPEAT_MODE_ONE`). It follows the
 * transport, so pausing the track stops the sleeve moving too.
 *
 * Where upstream pushed ExoPlayer's frames into a TextureView and read them
 * back off the GPU, here vlcj delivers them as memory callbacks — the GPU
 * readback upstream pays for is free, and the frame this composable hands the
 * palette pipeline is a downsample of the same copy the display draws.
 *
 * Nothing is drawn until the first frame arrives, and the fade in from there
 * means a failed or slow clip simply leaves the still art showing rather than
 * flashing a black square over it. [CanvasArtwork.fallbackUrl] gets one try if
 * the first rendition won't decode.
 */
@Composable
fun CanvasArtworkPlayer(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    contentMode: CanvasContentMode = CanvasContentMode.CROP,
    /** Align a contained portrait clip to the top; other callers retain centered FIT. */
    alignPortraitTop: Boolean = false,
    /** The decoded video's display aspect, or zero until libvlc knows it. */
    onAspectRatioChanged: (Float) -> Unit = {},
    /** Fades a full-player portrait clip away as the existing sleeve collapses. */
    presentationAlpha: () -> Float = { 1f },
    /** Fires once the clip has an actual frame on screen, and again if it drops back to none. */
    onRenderedChanged: (Boolean) -> Unit = {},
    /**
     * A frame off the playing clip, downscaled to [frameCapturePx] on its
     * longest edge, for callers that want to re-tint around it.
     */
    onFrameCaptured: (Bitmap) -> Unit = {},
    /**
     * Keep calling [onFrameCaptured] every so many milliseconds instead of
     * only once — for a caller re-tinting its backdrop off a playing clip,
     * which is worth following as it plays rather than settling on whatever
     * colours its opening frame happened to have. That holds for every
     * source, not just
     * [CanvasSource.SPOTIFY][com.music.bitchord.data.canvas.CanvasSource.SPOTIFY]:
     * a clip is a clip, and one that pans or cuts changes colour under its
     * own still sleeve exactly the same way regardless of who published it.
     * Null when a caller has nothing worth re-tinting off a moving colour at
     * all. Upstream's reason for keeping this opt-in was the GPU frame stall
     * each readback cost; here the frames already sit in memory and the cost
     * is only the 128px downsample, but the caller's contract is kept the
     * same.
     */
    refreshFrameEveryMs: Long? = null,
    /**
     * The longest edge of the bitmap [onFrameCaptured] is handed.
     *
     * This is the whole cost of following a clip — see [refreshFrameEveryMs].
     */
    frameCapturePx: Int = FRAME_CAPTURE_PX,
    /**
     * How much of whatever is behind the clip it is currently hiding: 0 while
     * nothing is drawn, ramping to 1 as the first frame fades in, and back
     * down if it drops out again.
     *
     * A caller that stacks a still image under the clip needs this to take
     * that image back out from under it, and cannot get there from
     * [onRenderedChanged] alone — that fires when the fade *starts*. It
     * matters most with [bottomFade]: one gradient over each of two stacked
     * layers leaves the lower one showing through the upper one instead of
     * the backdrop showing through both, so the still art stays half-visible
     * over the clip for as long as it is left lit underneath.
     */
    onCoverChanged: (Float) -> Unit = {},
    /**
     * Share of the clip's height, measured up from its bottom edge, over
     * which it dissolves to nothing — 0 for a hard edge.
     *
     * Upstream's reason for this being a parameter here rather than a mask
     * the caller draws — a Compose blend over a TextureView cannot reach the
     * video's own pixels — does not carry over: a draw-scope `DstIn` lands
     * exactly where the frame was drawn. It stays a parameter all the same,
     * because the fade has to know where the *video* ends: a contained
     * portrait clip dissolves at its own bottom edge, which only the mapping
     * in here knows.
     */
    bottomFade: Float = 0f,
    /**
     * Halts decoding for the length of a caller-driven transition — the sleeve
     * collapsing into the queue or lyrics panel and back — rather than only at
     * the two ends of it. The clip keeps its last frame on screen while
     * paused, so there is nothing to fade back in once it lifts.
     */
    pausedForTransition: Boolean = false,
) {
    var url by remember(canvas) { mutableStateOf(canvas.url) }
    var rendered by remember(canvas) { mutableStateOf(false) }
    // Aspect of the clip itself. Zero until the decoder reports it, which is
    // also the signal that there is nothing sensible to map the frame to yet.
    var clipAspect by remember(canvas) { mutableFloatStateOf(0f) }
    // Frames are counted rather than flagged, because [rendered] cannot answer
    // the question the watchdog below has to ask: "did a frame land on *this*
    // media", not "has one ever landed".
    var frameTick by remember(canvas) { mutableIntStateOf(0) }
    // The latest decoded frame, converted for display. Written at the display
    // cadence off the pending slot the video thread fills, so the draw phase
    // below reads a value that is stable for the frame it is drawing.
    var displayImage by remember(canvas) { mutableStateOf<ImageBitmap?>(null) }
    val currentContentMode by rememberUpdatedState(contentMode)
    val currentAlignPortraitTop by rememberUpdatedState(alignPortraitTop)
    val currentPresentationAlpha by rememberUpdatedState(presentationAlpha)
    val reportAspect by rememberUpdatedState(onAspectRatioChanged)
    val reportFrame by rememberUpdatedState(onFrameCaptured)

    // The player and its callbacks. `remember` unkeyed, like upstream's
    // ExoPlayer: one decoder for this mount's lifetime, re-pointed at each
    // media by [url] below rather than torn down per track — standing up a
    // libvlc instance per skip is exactly the cost the repository's Mutex
    // exists to keep off the wire.
    val surface = remember {
        CanvasVideoSurface(
            onFormatChanged = { aspect ->
                // The display size changed: the mapping below is stale until
                // the next frame arrives under it.
                if (aspect != clipAspect) rendered = false
                clipAspect = aspect
                reportAspect(aspect)
            },
            onFrame = {
                // The one proof that the current media, rather than some
                // previous one, is producing pixels.
                rendered = true
                frameTick++
            },
            onError = {
                // One retry, at the other rendition. If that is the one that
                // just failed there is nowhere left to go: leave the still
                // art up rather than looping through a broken URL.
                val alternate = canvas.fallbackUrl
                if (alternate != null && alternate != url) {
                    url = alternate
                } else {
                    rendered = false
                }
            },
        )
    }

    DisposableEffect(surface) {
        onDispose {
            // The parent owns the still/canvas handoff. Never leave it holding
            // a Success from a decoder that no longer exists.
            onRenderedChanged(false)
            onCoverChanged(0f)
            onAspectRatioChanged(0f)
            surface.release()
        }
    }

    LaunchedEffect(url) {
        rendered = false
        displayImage = null
        clipAspect = 0f
        reportAspect(0f)
        surface.play(CanvasCache.resolve(url))
    }

    // The repaint watchdog. Upstream's version fires when a TextureView is
    // handed back a surface it had lost and waits REPAINT_TIMEOUT_MS for real
    // pixels; a memory-callback surface here is never lost, so the one moment
    // that can still strand the caller on an empty clip is a media that
    // mounts and never produces a frame at all — and libvlc does not always
    // say so. Same constant, same verdict: if nothing landed by the deadline,
    // the still art stays up.
    LaunchedEffect(url) {
        val before = frameTick
        delay(REPAINT_TIMEOUT_MS)
        if (frameTick == before) {
            rendered = false
            Log.d(TAG, "no frame within ${REPAINT_TIMEOUT_MS}ms; leaving the still art up")
        }
    }

    // Gated on the caller's own state. Upstream also gated the decode on the
    // app being foregrounded, because a decoder running into an invisible
    // surface burns battery for the length of an album in a pocket; a desktop
    // window's composition pauses with it, which tears this composable down
    // with the same effect. [isPlaying] and [pausedForTransition] follow the
    // transport exactly as upstream's playWhenReady did.
    LaunchedEffect(surface, isPlaying, pausedForTransition) {
        surface.setPaused(!isPlaying || pausedForTransition)
    }

    // Publish frames at the display's own cadence: the video thread replaces
    // the pending slot as fast as the clip plays, and this loop converts only
    // the newest one per composited frame. Conversion is an array pass plus a
    // pixel install — see [CanvasFrame] for why no channel swap is involved.
    LaunchedEffect(surface) {
        while (isActive) {
            withFrameMillis {
                displayImage = surface.takeDisplayFrame(DISPLAY_MAX_EDGE)
            }
        }
    }

    val reportRendered by rememberUpdatedState(onRenderedChanged)
    LaunchedEffect(rendered) {
        reportRendered(rendered)
        if (!rendered) return@LaunchedEffect
        // Let the frame that triggered this actually be composited before
        // reading it back — grabbing it the instant the callback fires can
        // still catch the previous, empty pending slot.
        withFrameMillis { }
        surface.captureAt(frameCapturePx, currentContentMode, currentAlignPortraitTop)?.let {
            Log.d(TAG, "frame captured after rendered=true")
            reportFrame(it)
        }
    }

    // The opt-in follow-up to the capture above, for a caller that asked for
    // one — see [refreshFrameEveryMs]. A separate effect rather than a loop
    // folded into the one above: that one is keyed on [rendered] so it fires
    // again on every fade-in, and this one only needs to start once a fade-in
    // has actually happened and then keep going for as long as it holds.
    LaunchedEffect(rendered, refreshFrameEveryMs, frameCapturePx, clipAspect, contentMode, alignPortraitTop) {
        val interval = refreshFrameEveryMs ?: return@LaunchedEffect
        if (!rendered) return@LaunchedEffect
        while (isActive) {
            delay(interval)
            surface.captureAt(frameCapturePx, currentContentMode, currentAlignPortraitTop)?.let {
                reportFrame(it)
            }
        }
    }

    val alphaState = animateFloatAsState(
        targetValue = if (rendered) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "canvasAlpha",
    )

    // Published rather than left for the caller to mirror with a second
    // animation off [onRenderedChanged]: one fade, one account of how far
    // along it is. Zeroed on the way out, or a caller would be left holding
    // something hidden behind a clip that is no longer mounted.
    val reportCover by rememberUpdatedState(onCoverChanged)
    LaunchedEffect(Unit) {
        snapshotFlow { alphaState.value * currentPresentationAlpha() }.collect { reportCover(it) }
    }

    Canvas(
        modifier = modifier
            // Set on the layer rather than composed into the animation: a
            // fade driven by the caller's collapse re-runs this block rather
            // than recomposing the player around it — upstream's view.alpha
            // note, desktop spelling. The state's value is read here, in the
            // layer's own update pass, so neither the fade nor the collapse
            // recomposes this composable.
            .graphicsLayer {
                val hide = currentContentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect <= 0f
                alpha = if (hide) 0f else alphaState.value * currentPresentationAlpha()
                // The fade below erases part of what this layer drew, which
                // it can only do in a buffer of its own — the same offscreen
                // rule the hero's DstIn dissolve runs under.
                compositingStrategy = CompositingStrategy.Offscreen
            },
    ) {
        val image = displayImage
        if (image != null) {
            videoMapping(
                videoWidth = image.width,
                videoHeight = image.height,
                clipAspect = clipAspect,
                contentMode = currentContentMode,
                bounds = size,
                alignPortraitTop = currentAlignPortraitTop,
            )?.let { mapping ->
                drawImage(
                    image = image,
                    srcOffset = mapping.srcOffset,
                    srcSize = mapping.srcSize,
                    dstOffset = mapping.dstOffset,
                    dstSize = mapping.dstSize,
                    filterQuality = FilterQuality.Medium,
                )
                drawBottomFade(mapping, bottomFade)
            }
        }
    }
}

/** Where one frame lands inside the composable's bounds, in both spaces. */
private class VideoMapping(
    val srcOffset: IntOffset,
    val srcSize: IntSize,
    val dstOffset: IntOffset,
    val dstSize: IntSize,
)

/**
 * How a frame fills the bounds, worked out per drawn frame rather than per
 * pixel: cover by default, or contain for a portrait clip when the hero
 * explicitly requests it — the desktop spelling of upstream's
 * `applyContentTransform`, whose `Matrix` has no Compose equivalent. The same
 * centre-crop/centre-fit arithmetic, expressed as source and destination
 * rectangles for `drawImage`.
 */
private fun videoMapping(
    videoWidth: Int,
    videoHeight: Int,
    clipAspect: Float,
    contentMode: CanvasContentMode,
    bounds: Size,
    alignPortraitTop: Boolean,
): VideoMapping? {
    if (bounds.width < 1f || bounds.height < 1f || videoWidth < 1 || videoHeight < 1) return null
    val fit = contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect in 0.001f..0.999f
    return if (fit) {
        val scale = minOf(bounds.width / videoWidth, bounds.height / videoHeight)
        val width = (videoWidth * scale).toInt().coerceAtLeast(1)
        val height = (videoHeight * scale).toInt().coerceAtLeast(1)
        val left = ((bounds.width - width) / 2f).toInt().coerceAtLeast(0)
        val top = if (alignPortraitTop) 0 else ((bounds.height - height) / 2f).toInt().coerceAtLeast(0)
        VideoMapping(
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(videoWidth, videoHeight),
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(width, height),
        )
    } else {
        // Cover: the scale is decided by whichever axis runs out first, and
        // the source window is centred on the video.
        val scale = maxOf(bounds.width / videoWidth, bounds.height / videoHeight)
        val srcWidth = (bounds.width / scale).toInt().coerceIn(1, videoWidth)
        val srcHeight = (bounds.height / scale).toInt().coerceIn(1, videoHeight)
        VideoMapping(
            srcOffset = IntOffset((videoWidth - srcWidth) / 2, (videoHeight - srcHeight) / 2),
            srcSize = IntSize(srcWidth, srcHeight),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(bounds.width.toInt(), bounds.height.toInt()),
        )
    }
}

/**
 * Dissolves the mapped clip's bottom edge into whatever is behind it.
 *
 * A `DstIn` gradient over exactly the pixels `drawImage` laid down — the same
 * dissolve upstream gets from a `RenderEffect` on the TextureView's node, and
 * the same DstIn family the hero's still banner uses.
 */
private fun DrawScope.drawBottomFade(mapping: VideoMapping, fraction: Float) {
    if (fraction <= 0.001f) return
    val left = mapping.dstOffset.x.toFloat()
    val top = mapping.dstOffset.y.toFloat()
    val width = mapping.dstSize.width.toFloat()
    val height = mapping.dstSize.height.toFloat()
    val endY = top + height
    clipRect(left = left, top = top, right = left + width, bottom = endY) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = endY * (1f - fraction.coerceAtMost(1f)),
                endY = endY,
            ),
            topLeft = Offset(left, top),
            size = Size(width, height),
            blendMode = BlendMode.DstIn,
        )
    }
}

/**
 * The clip's decoder: one `MediaPlayerFactory`, one `EmbeddedMediaPlayer`, and
 * a callback video surface that lands decoded frames in memory.
 *
 * Created per [CanvasArtworkPlayer] mount and released with it — the shape
 * upstream's per-composition ExoPlayer had, with libvlc's instance costs in
 * place of Media3's. Never throws out of any entry point: canvas is
 * decoration, and a failure to decorate must leave the still art untouched
 * rather than reach the player (upstream `CanvasRepository.kt:27`'s principle,
 * carried to the rendering half of the family).
 */
private class CanvasVideoSurface(
    /** The decoded video's display aspect, or zero before the first format. */
    private val onFormatChanged: (Float) -> Unit,
    /** A frame landed on the current media. */
    private val onFrame: () -> Unit,
    /** The current media failed to play. */
    private val onError: () -> Unit,
) {
    /** The full-resolution frame the video thread last filled, and its counter. */
    private var pending: CanvasFrame? = null
    private var pendingGeneration = 0L

    /** The frame [takeDisplayFrame] last handed out, so it is never re-sent. */
    private var displayGeneration = 0L

    /** The display size the current format renders at — the frame's real shape. */
    private var renderWidth = 0
    private var renderHeight = 0

    private val formatCallback = object : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat =
            // The one format this surface accepts, exactly what vlcj's own
            // default callback returns: one RV32 plane, pitch = width * 4.
            RV32BufferFormat(sourceWidth, sourceHeight)

        override fun newFormatSize(sourceWidth: Int, sourceHeight: Int, displayWidth: Int, displayHeight: Int) {
            // The renderer's buffer is the *display* size — vlcj's own default
            // flow resizes its image here too, and the display callback copies
            // exactly this many ints out of the plane. Square-pixel clips
            // (every canvas source) have the two equal;
            // displayWidth/displayHeight is the one that carries the SAR.
            synchronized(this@CanvasVideoSurface) {
                renderWidth = displayWidth
                renderHeight = displayHeight
            }
            renderCallback.setBuffer(IntArray(displayWidth * displayHeight))
            onFormatChanged(
                if (displayWidth > 0 && displayHeight > 0) {
                    displayWidth.toFloat() / displayHeight
                } else 0f,
            )
        }

        override fun allocatedBuffers(buffers: Array<java.nio.ByteBuffer>) {
            // Nothing to keep: the adapter reads plane 0 through its own
            // int[] view, and libvlc writes the pixels on the other side.
        }
    }

    private val renderCallback = object : RenderCallbackAdapter() {
        override fun onDisplay(mediaPlayer: MediaPlayer, buffer: IntArray) {
            // The adapter reuses this one array for the next frame, so the
            // pixels leave with a copy. Called on a libvlc video thread.
            val width: Int
            val height: Int
            synchronized(this@CanvasVideoSurface) {
                width = renderWidth
                height = renderHeight
            }
            if (width > 0 && height > 0 && buffer.size >= width * height) {
                synchronized(this@CanvasVideoSurface) {
                    pending = CanvasFrame(buffer.copyOf(width * height), width, height)
                    pendingGeneration++
                }
                onFrame()
            }
        }
    }

    /**
     * The decoder behind every entry point, or null when libvlc could not be
     * stood up at all — every method then answers with its nothing, the same
     * answer a broken URL gets, and the still art stays up.
     */
    private var component: CallbackMediaPlayerComponent? = null
    private var factory: uk.co.caprica.vlcj.factory.MediaPlayerFactory? = null

    init {
        runCatching {
            // Headless by construction: no window is ever attached, and
            // --no-audio means a clip's soundtrack is never routed to an
            // output at all — the desktop spelling of upstream's disabled
            // audio track and zero volume. Its own factory, so none of this
            // can leak into the audio engine's player.
            val ownFactory = com.music.bitchord.desktop.playback.LibVlc.newFactory(
                "--quiet", "--no-video-title-show", "--no-audio",
            )
            val ownComponent = CallbackMediaPlayerComponent(
                ownFactory,
                null, // full-screen strategy — none, this surface is never shown
                InputEvents.NONE, // the clip takes no input
                true, // the component owns and releases its player
                null, // no Swing painter — the frames are read out below
                renderCallback,
                formatCallback,
                null, // no Swing video surface component
            )
            // Loop on the one media, and keep the belt-and-braces half of
            // upstream's muting in case --no-audio is ever relaxed.
            ownComponent.mediaPlayer().controls().setRepeat(true)
            ownComponent.mediaPlayer().audio().setVolume(0)
            ownComponent.mediaPlayer().audio().setTrack(-1)
            ownComponent.mediaPlayer().events().addMediaPlayerEventListener(
                object : MediaPlayerEventAdapter() {
                    override fun error(mediaPlayer: MediaPlayer) {
                        onError()
                    }
                },
            )
            factory = ownFactory
            component = ownComponent
        }.onFailure {
            Log.w(TAG, "canvas decoder unavailable: ${it.message}")
            runCatching { factory?.release() }
            factory = null
        }
    }

    /**
     * Mount [mrl] — a remote URL, or the local path [CanvasCache] answered
     * with — as the one looping media.
     */
    fun play(mrl: String) {
        runCatching {
            val player = component?.mediaPlayer() ?: return
            player.controls().stop()
            player.media().play(mrl)
            Log.d(TAG, "playing ${mrl.takeLast(48)}")
        }.onFailure {
            Log.w(TAG, "canvas play failed: ${it.message}")
            onError()
        }
    }

    /** Follows the transport: the clip pauses when the track does. */
    fun setPaused(paused: Boolean) {
        runCatching { component?.mediaPlayer()?.controls()?.setPause(paused) }
    }

    /**
     * The newest frame, converted to a drawable bitmap, or null when nothing
     * new has landed since the last call. Capped to [maxEdge] on its long
     * edge by nearest-neighbour sampling in the same pass that lays the
     * pixels out — see [DISPLAY_MAX_EDGE].
     */
    fun takeDisplayFrame(maxEdge: Int): ImageBitmap? {
        val frame = synchronized(this) {
            val current = pending
            if (current == null || pendingGeneration == displayGeneration) return null
            displayGeneration = pendingGeneration
            current
        }
        val scaled = downsampled(frame, maxEdge) ?: return null
        return argbImageBitmap(scaled.pixels, scaled.width, scaled.height)
    }

    /**
     * A frame no bigger than [maxPx] on its longest edge, for the palette
     * pipeline — see [FRAME_CAPTURE_PX].
     *
     * The aspect is kept rather than squared off. Nothing downstream draws
     * this, but everything downstream *averages* it, and squashing one axis
     * would quietly reweight which part of the frame each average is mostly
     * made of. In portrait-fit mode the contain margins are excluded for the
     * same reason upstream's `captureAt` excluded them: both consumers
     * average RGB, and the margins are black.
     */
    fun captureAt(maxPx: Int, contentMode: CanvasContentMode, alignPortraitTop: Boolean): Bitmap? {
        val frame = synchronized(this) { pending } ?: return null
        if (frame.width < 1 || frame.height < 1) return null
        return runCatching {
            val scale = maxPx.toFloat() / maxOf(frame.width, frame.height)
            val small = if (scale >= 1f) frame else downsampled(frame, maxPx) ?: return null
            if (contentMode != CanvasContentMode.FIT_PORTRAIT ||
                (frame.width.toFloat() / frame.height) >= 1f
            ) {
                return argbBitmap(small.pixels, small.width, small.height)
            }

            // Exclude the transparent contain margins before handing pixels
            // to the mesh or palette (both average RGB). Same arithmetic as
            // upstream's captureAt, over the frame's own dimensions.
            val viewAspect = small.width.toFloat() / small.height
            val clipAspect = frame.width.toFloat() / frame.height
            val contentWidth = if (clipAspect < viewAspect) small.height * clipAspect else small.width.toFloat()
            val contentHeight = if (clipAspect < viewAspect) small.height.toFloat() else small.width / clipAspect
            val left = ceil((small.width - contentWidth) / 2f).toInt().coerceIn(0, small.width - 1)
            val top = if (alignPortraitTop) 0 else
                ceil((small.height - contentHeight) / 2f).toInt().coerceIn(0, small.height - 1)
            val right = (small.width - left).coerceAtLeast(left + 1)
            val bottom = if (alignPortraitTop) contentHeight.toInt().coerceIn(1, small.height) else
                (small.height - top).coerceAtLeast(top + 1)
            val cropped = IntArray((right - left) * (bottom - top))
            var row = 0
            while (row < bottom - top) {
                System.arraycopy(
                    small.pixels,
                    (top + row) * small.width + left,
                    cropped,
                    row * (right - left),
                    right - left,
                )
                row++
            }
            argbBitmap(cropped, right - left, bottom - top)
        }.getOrNull()
    }

    fun release() {
        runCatching { component?.release() }
        runCatching { factory?.release() }
        component = null
        factory = null
    }

    /** Nearest-neighbour resample to at most [maxEdge] on the long edge. */
    private fun downsampled(frame: CanvasFrame, maxEdge: Int): CanvasFrame? {
        if (frame.width < 1 || frame.height < 1) return null
        val longest = maxOf(frame.width, frame.height)
        if (longest <= maxEdge) return frame
        val scale = longest.toFloat() / maxEdge
        val width = (frame.width / scale).toInt().coerceIn(1, frame.width)
        val height = (frame.height / scale).toInt().coerceIn(1, frame.height)
        val out = IntArray(width * height)
        val xStep = frame.width.toFloat() / width
        val yStep = frame.height.toFloat() / height
        var y = 0
        while (y < height) {
            val srcY = (y * yStep).toInt().coerceIn(0, frame.height - 1)
            val rowAt = srcY * frame.width
            var x = 0
            while (x < width) {
                val srcX = (x * xStep).toInt().coerceIn(0, frame.width - 1)
                out[y * width + x] = frame.pixels[rowAt + srcX]
                x++
            }
            y++
        }
        return CanvasFrame(out, width, height)
    }
}

/**
 * ARGB ints as a drawable image — the write side of the pixel adapter the
 * palette reads (`SkiaPixels.argbPixels`), naming `RGBA_8888` for the same
 * reason that file does: the byte order laid down has to be knowable from
 * this file, not inherited from `allocN32Pixels`'s platform choice.
 */
private fun argbImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap? =
    argbBitmap(pixels, width, height)?.asComposeImageBitmap()

/**
 * The same pixels as a [Bitmap], for callers that re-read them
 * ([FrameHeuristics]). Null when Skia refuses the install or the shape is
 * wrong — the same "no answer" a failed pixel read means, and callers treat
 * it that way.
 */
private fun argbBitmap(pixels: IntArray, width: Int, height: Int): Bitmap? {
    if (width < 1 || height < 1 || pixels.size < width * height) return null
    return runCatching {
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val bytes = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            val argb = pixels[i]
            val at = i * 4
            bytes[at] = (argb shr 16 and 0xFF).toByte()
            bytes[at + 1] = (argb shr 8 and 0xFF).toByte()
            bytes[at + 2] = (argb and 0xFF).toByte()
            bytes[at + 3] = (argb shr 24 and 0xFF).toByte()
        }
        val bitmap = Bitmap()
        if (!bitmap.installPixels(info, bytes, info.minRowBytes)) return null
        bitmap
    }.getOrNull()
}
