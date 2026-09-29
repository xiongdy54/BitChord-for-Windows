package com.music.bitchord.ui.theme

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * A Skia bitmap's pixels as ARGB ints — the form the quantiser reads.
 *
 * Asking for RGBA_8888 rather than trusting whatever the decoder produced: Skia
 * converts during the read, so the byte order below is known rather than
 * assumed.
 */
internal fun Bitmap.argbPixels(): IntArray? {
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    val bytes = readPixels(info, width * 4, 0, 0) ?: return null
    return IntArray(width * height) { i ->
        val at = i * 4
        (0xFF shl 24) or
            ((bytes[at].toInt() and 0xFF) shl 16) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            (bytes[at + 2].toInt() and 0xFF)
    }
}
