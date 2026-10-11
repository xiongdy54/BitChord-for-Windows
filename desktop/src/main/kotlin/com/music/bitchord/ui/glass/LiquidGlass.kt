package com.music.bitchord.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import kotlin.math.roundToInt

/**
 * `Modifier.liquidGlass` — the true glass. Upstream `ui/components/LiquidGlass.kt`'s
 * modifier, re-bottomed on skiko.
 *
 * What a frame draws, in order (upstream `DrawBackdropModifier.kt`'s pass,
 * flattened to what this one surface needs):
 *
 *  1. **Sample** the backdrop region under this node into a layer recorded at
 *     1/3 linear resolution — the scale is the performance feature, and every
 *     pixel uniform below is stated in that downscaled space.
 *  2. **Filter** the sample: saturation 1.5 → blur → lens refraction with
 *     chromatic dispersion, one `ImageFilter` chain hung on the layer's
 *     `renderEffect`.
 *  3. **Shadow** — a blurred dark under-rrect, upstream `Shadow.Default`.
 *  4. **Glass + tint** — the filtered sample stretched back up and clipped to
 *     the shape, under the theme's surface tint at 40%.
 *  5. **Highlight** — a stroked, blurred rim riding the corner gradient, the
 *     shader that tells the eye where the glass ends.
 *  6. **Content** — whatever the caller put inside.
 *
 * The gating is upstream's: sites decide *whether* to call this (they read
 * [LocalLiquidGlassEnabled]); the modifier itself handles *how* — the solid
 * fallback when reduce-dynamic-blur is on, and a no-op when Skia will not
 * compile the shaders.
 */
@Composable
fun Modifier.liquidGlass(shape: CornerBasedShape): Modifier {
    if (!GlassEffects.isGlassSupported) {
        TrackLog.w("BitChord", "liquid glass: Skia refused the shaders; drawing plain")
        return this
    }
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsState()
    if (reduceDynamicBlur) {
        return this
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
    }
    val backdrop = LocalAppBackdrop.current ?: return this

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val myOffset = remember { mutableStateOf(Offset.Zero) }
    val sampleLayer = rememberGraphicsLayer()
    val currentShape by rememberUpdatedState(shape)
    val currentBackdrop by rememberUpdatedState(backdrop)

    val surfaceTintColor = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFFFAFAFA)
    } else {
        Color(0xFF121212)
    }

    return this
        .onGloballyPositioned { myOffset.value = it.positionInRoot() }
        .drawWithContent {
            val shape = currentShape
            val backdrop = currentBackdrop
            val s = GLASS_RESOLUTION_SCALE

            // 1. The sample: the backdrop's own recorded layer, drawn shifted
            //    so the region under this node lands at the sample's origin,
            //    scaled into the 1/3-resolution layer.
            val sampleSize = IntSize(
                (size.width * s).roundToInt().coerceAtLeast(1),
                (size.height * s).roundToInt().coerceAtLeast(1),
            )
            val delta = backdrop.rootPosition - myOffset.value
            sampleLayer.record(density, layoutDirection, sampleSize) {
                withTransform({
                    scale(s, s, pivot = Offset.Zero)
                    translate(left = delta.x, top = delta.y)
                }) {
                    drawLayer(backdrop.layer)
                }
            }

            // 2. The chain. Pixel uniforms are the SAMPLE's — the shader sees
            //    the downscaled world it samples (upstream's
            //    BackdropEffectScope.update rule).
            val cornerRadii = GlassEffects.run { cornerRadiiOf(shape, layoutDirection) }
            val sampleCorner = cornerRadii.map { it * s }.toFloatArray()
            sampleLayer.renderEffect = GlassEffects.glassFilter(
                sampleSize = sampleSize,
                cornerRadiiPx = sampleCorner,
                blurRadiusPx = with(density) { BLUR_RADIUS_DP.dp.toPx() } * s,
                refractionHeightPx = with(density) { (LENS_HEIGHT * LENS_MAX_DP).dp.toPx() } * s,
                refractionAmountPx = with(density) { (LENS_AMOUNT * LENS_MAX_DP).dp.toPx() } * s,
            ).asComposeRenderEffect()

            val outline = shape.createOutline(size, layoutDirection, this)
            val clipPath = Path().apply { addOutline(outline) }

            // 3. The shadow, beneath everything — upstream Shadow.Default:
            //    radius 24dp, offset (0, radius/6), black 10%.
            val shadowRadius = with(density) { 24.dp.toPx() }
            val skia = drawContext.canvas.nativeCanvas
            skia.drawGlassRRect(
                outline = outline,
                center = Offset.Zero + Offset(0f, shadowRadius / 6f),
                color = Color.Black.copy(alpha = 0.10f),
                stroked = false,
                blurRadius = shadowRadius,
            )

            // 4. The glass itself and the theme tint over it, clipped to the
            //    shape the caller gave.
            clipPath(clipPath) {
                withTransform({ scale(1f / s, 1f / s, pivot = Offset.Zero) }) {
                    drawLayer(sampleLayer)
                }
                drawRect(surfaceTintColor.copy(alpha = SURFACE_OPACITY))
            }

            // 5. The rim: the highlight shader through a blurred stroke.
            skia.drawGlassRRect(
                outline = outline,
                center = Offset.Zero,
                color = Color.White.copy(alpha = 0.5f),
                stroked = true,
                blurRadius = with(density) { (HIGHLIGHT_WIDTH.dp.toPx() / 2f).coerceAtLeast(0.1f) },
                cornerRadii = cornerRadii,
                useHighlightShader = true,
                densityF = density.density,
            )

            // 6. The content.
            drawContent()
        }
}

/** Upstream `LiquidGlass.kt`'s Apple-matched recipe and hairline, verbatim. */
object LiquidGlassDefaults {
    internal const val VIBRANCY = 1f
    internal const val BLUR_RADIUS_DP = 8f
    internal const val LENS_HEIGHT = 0.5f
    internal const val LENS_AMOUNT = 0.5f
    internal const val LENS_MAX_DP = 48f
    internal const val SURFACE_OPACITY = 0.4f
    internal const val GLASS_RESOLUTION_SCALE = 0.33f
    internal val GLASS_EDGE_WIDTH = 0.5.dp
    internal val GLASS_EDGE_COLOR = Color.White.copy(alpha = 0.10f)

    /** Upstream `Highlight.Default`'s width; its blur is half of it. */
    internal const val HIGHLIGHT_WIDTH = 0.5f
}

private val GLASS_EDGE_WIDTH = LiquidGlassDefaults.GLASS_EDGE_WIDTH
private val GLASS_EDGE_COLOR = LiquidGlassDefaults.GLASS_EDGE_COLOR
private val GLASS_RESOLUTION_SCALE = LiquidGlassDefaults.GLASS_RESOLUTION_SCALE
private val BLUR_RADIUS_DP = LiquidGlassDefaults.BLUR_RADIUS_DP
private val LENS_HEIGHT = LiquidGlassDefaults.LENS_HEIGHT
private val LENS_AMOUNT = LiquidGlassDefaults.LENS_AMOUNT
private val LENS_MAX_DP = LiquidGlassDefaults.LENS_MAX_DP
private val SURFACE_OPACITY = LiquidGlassDefaults.SURFACE_OPACITY
private val HIGHLIGHT_WIDTH = LiquidGlassDefaults.HIGHLIGHT_WIDTH

/**
 * One skia rrect under the glass: the shadow's blurred dark fill below, or the
 * highlight's blurred shader-driven stroke on top.
 *
 * This is the one place the pipeline touches skia directly — the compose paint
 * API has no mask filter, and the rim's blur is what makes it read as glass
 * rather than as a border.
 */
private fun org.jetbrains.skia.Canvas.drawGlassRRect(
    outline: Outline,
    center: Offset,
    color: Color,
    stroked: Boolean,
    blurRadius: Float,
    cornerRadii: FloatArray = FloatArray(4),
    useHighlightShader: Boolean = false,
    densityF: Float = 1f,
) {
    val rounded = outline as? Outline.Rounded ?: return
    val rr = rounded.roundRect
    val radii = floatArrayOf(
        rr.topLeftCornerRadius.x, rr.topLeftCornerRadius.x,
        rr.topRightCornerRadius.x, rr.topRightCornerRadius.x,
        rr.bottomRightCornerRadius.x, rr.bottomRightCornerRadius.x,
        rr.bottomLeftCornerRadius.x, rr.bottomLeftCornerRadius.x,
    )
    val rrect = org.jetbrains.skia.RRect.makeComplexLTRB(
        rr.left, rr.top + center.y, rr.right, rr.bottom + center.y,
        radii,
    )
    val paint = org.jetbrains.skia.Paint()
    paint.isAntiAlias = true
    if (useHighlightShader) {
        // The rim rides the corner gradient: the shader knows where the edge
        // is, the stroke only carries it, and the blur is what makes it glass
        // rather than a border.
        paint.mode = org.jetbrains.skia.PaintMode.STROKE
        paint.strokeWidth = HIGHLIGHT_WIDTH * densityF
        paint.strokeCap = org.jetbrains.skia.PaintStrokeCap.ROUND
        paint.shader = GlassEffects.highlightShader(
            size = androidx.compose.ui.geometry.Size(rr.right - rr.left, rr.bottom - rr.top),
            cornerRadiiPx = cornerRadii,
            color = color,
            angleRadians = Math.toRadians(45.0).toFloat(),
            falloff = 1f,
        )
        paint.blendMode = org.jetbrains.skia.BlendMode.PLUS
    } else {
        paint.color = color.toArgb()
    }
    paint.maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(
        org.jetbrains.skia.FilterBlurMode.NORMAL,
        blurRadius,
        true,
    )
    drawRRect(rrect, paint)
}

