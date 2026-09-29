package com.music.bitchord.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

// Desktop replacements for androidx.core.graphics.ColorUtils and the two
// luminance helpers the Android ArtworkPalette.kt carried inline. The maths is
// the same as upstream's; only the host API changes.

/** `ColorUtils.colorToHSL` — H 0..360, S and L 0..1. */
internal fun colorToHsl(argb: Int): FloatArray {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    val delta = max - min
    if (delta == 0f) return floatArrayOf(0f, 0f, lightness)
    val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val hue = when (max) {
        r -> ((g - b) / delta + if (g < b) 6f else 0f)
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    } * 60f
    return floatArrayOf(hue, saturation, lightness)
}

/** `ColorUtils.HSLToColor` — the standard HSL→RGB, component for component. */
internal fun hslToColor(hsl: FloatArray): Color {
    val h = hsl[0] / 360f
    val s = hsl[1].coerceIn(0f, 1f)
    val l = hsl[2].coerceIn(0f, 1f)
    if (s == 0f) return Color(l, l, l)
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun channel(t: Float): Float {
        var x = t
        if (x < 0f) x += 1f
        if (x > 1f) x -= 1f
        return when {
            x < 1f / 6f -> p + (q - p) * 6f * x
            x < 1f / 2f -> q
            x < 2f / 3f -> p + (q - p) * (2f / 3f - x) * 6f
            else -> p
        }
    }
    return Color(channel(h + 1f / 3f), channel(h), channel(h - 1f / 3f))
}

/**
 * WCAG relative luminance: average in linear light, never gamma-encoded RGB.
 */
internal fun relativeLuminance(argb: Int): Float {
    fun linear(channel: Int): Float {
        val srgb = channel / 255f
        return if (srgb <= 0.04045f) {
            srgb / 12.92f
        } else {
            ((srgb + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
    }
    return 0.2126f * linear(argb shr 16 and 0xFF) +
        0.7152f * linear(argb shr 8 and 0xFF) +
        0.0722f * linear(argb and 0xFF)
}

internal fun averageRelativeLuminance(pixels: IntArray): Float {
    if (pixels.isEmpty()) return 0f
    return pixels.sumOf { relativeLuminance(it).toDouble() }.div(pixels.size).toFloat()
}

/**
 * Keeps neutral artwork neutral instead of inventing a hue for it.
 *
 * HSL represents grey with hue zero; raising that grey to a saturation floor
 * therefore manufactures red. A small real amount of colour is kept as-is,
 * while an unmistakably chromatic swatch can still be strengthened enough to
 * make controls legible and the page recognisable.
 */
internal fun adaptedArtworkSaturation(source: Float, minimum: Float, maximum: Float): Float {
    val saturation = source.coerceIn(0f, 1f)
    return if (saturation < CHROMATIC_SATURATION_THRESHOLD) {
        saturation
    } else {
        saturation.coerceIn(minimum, maximum)
    }
}

/** Below this, boosting saturation makes quantisation noise visible as a tint. */
internal const val CHROMATIC_SATURATION_THRESHOLD = 0.12f
