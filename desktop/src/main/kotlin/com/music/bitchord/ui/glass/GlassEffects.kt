package com.music.bitchord.ui.glass

import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * The glass effect chain, built on skiko where upstream built it on
 * `android.graphics.RenderEffect`.
 *
 * The mapping is one-to-one, because both bottom out in the same Skia:
 *
 * | upstream (android.graphics)                | here (skiko)                          |
 * |--------------------------------------------|---------------------------------------|
 * | `RuntimeShader(agsl)`                      | `RuntimeEffect.makeForShader(sksl)`   |
 * | `createRuntimeShaderEffect(s, "content")`  | `ImageFilter.makeRuntimeShader(...)`  |
 * | `createChainEffect(outer, inner)`          | nested `input` parameters             |
 * | `createColorFilterEffect(cf, inner)`       | `ImageFilter.makeColorFilter(...)`    |
 * | `BlurMaskFilter(radius)` (highlight)       | `MaskFilter.makeBlur(NORMAL, radius)` |
 *
 * Order matters and is upstream's: **saturation → blur → refraction** — the
 * lens is the outermost filter and its `content` uniform receives the blurred,
 * saturated backdrop, exactly what `RenderEffect.createChainEffect` folded
 * when the effects were declared `colorControls(); blur(); lens()`.
 */
internal object GlassEffects {

    private val compiled = ConcurrentHashMap<String, RuntimeEffect>()

    /**
     * One compile per shader per process. `makeForShader` throws on bad SkSL,
     * and a machine whose Skia rejects a shader must say so once, not per
     * frame — [isGlassSupported] catches that answer at startup.
     */
    fun compile(sksl: String): RuntimeEffect =
        compiled.computeIfAbsent(sksl) { RuntimeEffect.Companion.makeForShader(it) }

    /** Whether every glass shader compiles on this machine's Skia. Asked once, remembered. */
    @Volatile
    private var supported: Boolean? = null

    val isGlassSupported: Boolean
        get() = supported ?: run {
            val ok = listOf(GlassShaders.refractionWithDispersion, GlassShaders.defaultHighlight).all { sksl ->
                runCatching { compile(sksl) }.isSuccess
            }
            supported = ok
            ok
        }

    /**
     * The full glass chain for a sample recorded at [sampleSize] pixels.
     *
     * Every uniform is in **sample pixels** — the layer this filter attaches
     * to was recorded at GLASS_RESOLUTION_SCALE of the real thing, which is
     * upstream `BackdropEffectScope.update`'s rule: the shader sees the
     * downscaled world it actually samples.
     *
     * @param cornerRadiiPx [TL, TR, BR, BL] in sample pixels, pre-clamped.
     */
    fun glassFilter(
        sampleSize: IntSize,
        cornerRadiiPx: FloatArray,
        blurRadiusPx: Float,
        refractionHeightPx: Float,
        refractionAmountPx: Float,
    ): ImageFilter {
        val builder = RuntimeShaderBuilder(compile(GlassShaders.refractionWithDispersion))
        builder.uniform("size", sampleSize.width.toFloat(), sampleSize.height.toFloat())
        // No blur-edge padding on the desktop: the glass sits inset inside the
        // player, so the clamp smear at the sample's edge never shows.
        builder.uniform("offset", 0f, 0f)
        builder.uniform(
            "cornerRadii",
            cornerRadiiPx[0], cornerRadiiPx[1], cornerRadiiPx[2], cornerRadiiPx[3],
        )
        builder.uniform("refractionHeight", refractionHeightPx)
        // Upstream hands the shader a negated amount — the lens pulls inward.
        builder.uniform("refractionAmount", -refractionAmountPx)
        builder.uniform("depthEffect", 1f)
        builder.uniform("chromaticAberration", 1f)

        val saturated = ImageFilter.makeColorFilter(
            ColorFilter.makeMatrix(saturationMatrix(SURFACE_SATURATION)),
            null,
            null,
        )
        val blurred = ImageFilter.makeBlur(
            blurRadiusPx,
            blurRadiusPx,
            FilterTileMode.CLAMP,
            saturated,
            null,
        )
        return ImageFilter.makeRuntimeShader(builder, "content", blurred)
    }

    /**
     * The rim shader for the highlight pass, packed the way the SkSL declares
     * its uniforms: size(2) → cornerRadii(4) → color(4) → angle → falloff.
     * Coordinates are the *drawn* stroke's own pixel space, not the sample's —
     * the highlight rides the glass at full resolution.
     */
    fun highlightShader(
        size: Size,
        cornerRadiiPx: FloatArray,
        color: Color,
        angleRadians: Float,
        falloff: Float,
    ): Shader {
        val builder = RuntimeShaderBuilder(compile(GlassShaders.defaultHighlight))
        builder.uniform("size", size.width, size.height)
        builder.uniform(
            "cornerRadii",
            cornerRadiiPx[0], cornerRadiiPx[1], cornerRadiiPx[2], cornerRadiiPx[3],
        )
        builder.uniform("color", color.toArgbInt())
        builder.uniform("angle", angleRadians)
        builder.uniform("falloff", falloff)
        return builder.makeShader()
    }

    /**
     * The corner radii [shape] asks for at the current draw scope's size, as
     * the shaders read them: [TL, TR, BR, BL] px, each clamped to half the
     * shorter side — the clamp upstream's radius geometry implies, stated here
     * so a pill-shaped 999dp radius cannot ask the SDF for the impossible.
     */
    fun DrawScope.cornerRadiiOf(shape: Shape, layoutDirection: LayoutDirection): FloatArray {
        val outline = shape.createOutline(size, layoutDirection, this)
        val clamp = min(size.width, size.height) / 2f
        val radii = (outline as? Outline.Rounded)?.roundRect ?: return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(
            radii.topLeftCornerRadius.x.coerceAtMost(clamp),
            radii.topRightCornerRadius.x.coerceAtMost(clamp),
            radii.bottomRightCornerRadius.x.coerceAtMost(clamp),
            radii.bottomLeftCornerRadius.x.coerceAtMost(clamp),
        )
    }

    /**
     * Saturation-only ColorMatrix — upstream `colorControls(saturation)` rides
     * a 4x5 matrix whose luminance row is the standard Rec. 601 one.
     */
    internal fun saturationMatrix(saturation: Float): ColorMatrix =
        ColorMatrix(
            0.213f + 0.787f * saturation, 0.715f - 0.715f * saturation, 0.072f - 0.072f * saturation, 0f, 0f,
            0.213f - 0.213f * saturation, 0.715f + 0.285f * saturation, 0.072f - 0.072f * saturation, 0f, 0f,
            0.213f - 0.213f * saturation, 0.715f - 0.715f * saturation, 0.072f + 0.928f * saturation, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )

    /** Upstream `colorControls`'s saturation for `VIBRANCY = 1f`: `1f + 0.5f * VIBRANCY`. */
    internal const val SURFACE_SATURATION = 1.5f

    private fun Color.toArgbInt(): Int {
        val a = (alpha * 255f).toInt() shl 24
        val r = (red * 255f).toInt() shl 16
        val g = (green * 255f).toInt() shl 8
        val b = (blue * 255f).toInt()
        return a or r or g or b
    }
}
