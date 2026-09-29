package com.music.bitchord.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.sqrt

// The desktop stand-in for androidx.palette: the Android quantiser (median cut
// over the artwork, scored so that a colour nobody sees enough of never
// becomes the accent) reduced to what ArtworkPalette.kt actually reads from it.

private const val SWATCH_COUNT = 24

/** Bottom 18%: what a blur wide enough to lose the picture leaves behind there. */
private const val EDGE_BAND = 0.18f

/** Top 10%: the band the phone's status-bar glyphs sit over. */
private const val TOP_BAND = 0.10f

internal data class Swatch(val rgb: Int, val population: Int)

/** The raw artwork colours: what the page is mostly made of, its brightest note, and what its bottom edge averages out to. */
internal data class ArtworkSeed(
    val dominant: Color,
    val vibrant: Color,
    val edge: Color,
    val topBandLuminance: Float,
)

/**
 * Median cut: repeatedly take the box with the most pixels, find the channel it
 * spans widest, and split the box there at that channel's median. Ends when it
 * runs out of splittable boxes or reaches [maxColors].
 */
internal fun medianCutSwatches(pixels: IntArray, maxColors: Int): List<Swatch> {
    if (pixels.isEmpty()) return emptyList()
    var boxes: List<List<Int>> = listOf(pixels.toList())
    while (boxes.size < maxColors) {
        val box = boxes.maxByOrNull { it.size } ?: break
        if (box.size < 2) break
        var channel = 0
        var widest = -1
        for (c in 0..2) {
            val shift = 16 - c * 8
            var min = 255
            var max = 0
            for (pixel in box) {
                val v = pixel shr shift and 0xFF
                if (v < min) min = v
                if (v > max) max = v
            }
            if (max - min > widest) {
                widest = max - min
                channel = c
            }
        }
        if (widest <= 0) break
        val shift = 16 - channel * 8
        val sorted = box.sortedBy { it shr shift and 0xFF }
        val mid = sorted.size / 2
        boxes = boxes.filterNot { it === box } +
            listOf(sorted.subList(0, mid), sorted.subList(mid, sorted.size))
    }
    return boxes.filter { it.isNotEmpty() }.map { box ->
        var r = 0L
        var g = 0L
        var b = 0L
        for (pixel in box) {
            r += pixel shr 16 and 0xFF
            g += pixel shr 8 and 0xFF
            b += pixel and 0xFF
        }
        val n = box.size
        Swatch(
            rgb = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt(),
            population = n,
        )
    }
}

/**
 * The Android app reads two androidx Palettes — one with the default filter,
 * one cleared — and takes the dominant colour from the first and the accent
 * from the second. One median cut plus the same two judgements gets there:
 * dominant by population, vibrant by `saturation × √population`, so a sleeve
 * that is four-fifths black sky cannot accent in black.
 */
internal fun seedOf(pixels: IntArray, width: Int, height: Int): ArtworkSeed? {
    val swatches = medianCutSwatches(pixels, SWATCH_COUNT)
    if (swatches.isEmpty()) return null
    // The default filter, approximated: near-black, near-white and near-grey
    // swatches are not accents.
    val accentCandidates = swatches.filter { swatch ->
        val hsl = colorToHsl(swatch.rgb)
        hsl[2] in 0.12f..0.92f && hsl[1] >= CHROMATIC_SATURATION_THRESHOLD
    }.ifEmpty { swatches }
    val dominant = swatches.maxBy { it.population }
    val vibrant = accentCandidates.maxBy { colorToHsl(it.rgb)[1] * sqrt(it.population.toFloat()) }
    return ArtworkSeed(
        dominant = Color(dominant.rgb),
        vibrant = Color(vibrant.rgb),
        edge = bottomEdgeColor(pixels, width, height),
        topBandLuminance = topBandRelativeLuminance(pixels, width, height),
    )
}

/**
 * The mean of the artwork's bottom band — what a blur wide enough to lose the
 * picture leaves behind at that edge. A flat mean rather than a quantised
 * swatch on purpose: a blur has no notion of which colour is *important*, and
 * the page under the artwork has to match what the blur actually produced.
 */
internal fun bottomEdgeColor(pixels: IntArray, width: Int, height: Int): Color {
    val band = (height * EDGE_BAND).toInt().coerceIn(1, height)
    var r = 0L
    var g = 0L
    var b = 0L
    var count = 0
    for (y in (height - band) until height) {
        for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            r += pixel shr 16 and 0xFF
            g += pixel shr 8 and 0xFF
            b += pixel and 0xFF
            count++
        }
    }
    if (count == 0) return Color.Black
    return Color((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
}

/** The status inset occupies only the upper sliver, so the band is kept tight. */
internal fun topBandRelativeLuminance(pixels: IntArray, width: Int, height: Int): Float {
    val band = (height * TOP_BAND).toInt().coerceIn(1, height)
    val slice = IntArray(width * band)
    for (y in 0 until band) {
        for (x in 0 until width) slice[y * width + x] = pixels[y * width + x]
    }
    return averageRelativeLuminance(slice)
}
