package com.music.bitchord.ui.theme

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
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

/**
 * ARGB ints as an [ImageBitmap] — the write side of [argbPixels], and the
 * desktop spelling of the Android original's
 * `Bitmap.createBitmap(texels, w, h, ARGB_8888).asImageBitmap()`.
 *
 * RGBA_8888 is named rather than left to `allocN32Pixels`, which picks RGBA on
 * big-endian and BGRA on little-endian: the byte order laid down here has to be
 * the one [argbPixels] reads back, and it has to be knowable from this file.
 *
 * Null when there is no image in what was handed over, or when Skia refuses the
 * install — the same "no answer" a failed pixel read means, and callers treat it
 * that way.
 */
internal fun argbImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap? {
    if (width < 1 || height < 1 || pixels.size < width * height) return null
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
    return if (bitmap.installPixels(info, bytes, info.minRowBytes)) bitmap.asComposeImageBitmap() else null
}
