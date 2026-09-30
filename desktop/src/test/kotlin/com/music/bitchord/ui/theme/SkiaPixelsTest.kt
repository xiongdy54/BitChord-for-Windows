package com.music.bitchord.ui.theme

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The mesh texture is built from an IntArray on a worker thread and then drawn
 * for the rest of the cover's life, so three things about [argbImageBitmap]
 * have to be true and none is checkable by eye: the four bytes it lays down are
 * the channels Skia thinks they are, the pixels stay correct after the array
 * they came from has been collected or moved, and Compose can draw the result at
 * the place and size it is given.
 */
class SkiaPixelsTest {

    /** Channel-order and row-stride in one: no pixel shares a channel value. */
    private val source = intArrayOf(
        0xFF123456.toInt(), 0xFF789ABC.toInt(), 0xFFDE_F01E.toInt(),
        0xFF01_0203.toInt(), 0xFF04_0506.toInt(), 0xFF07_0809.toInt(),
    )

    @Test
    fun `an argb array round trips through the bitmap it makes`() {
        val image = assertNotNull(argbImageBitmap(source, 3, 2))
        assertEquals(3, image.width)
        assertEquals(2, image.height)
        assertEquals(source.toList(), image.asSkiaBitmap().argbPixels()?.toList())
    }

    /**
     * The round trip above would still pass if both ends made the same wrong
     * guess about which byte is which channel. `getColor` is Skia's own answer
     * for a named RGBA_8888 bitmap, so this is the one that says the bytes went
     * down R,G,B,A — the same reading [argbPixels] assumes when it asks Skia for
     * RGBA_8888 and takes byte zero as red.
     */
    @Test
    fun `skia itself reads the channels back in argb order`() {
        val bitmap = assertNotNull(argbImageBitmap(source, 3, 2)).asSkiaBitmap()
        assertEquals(0xFF123456.toInt(), bitmap.getColor(0, 0))
        assertEquals(0xFFDEF01E.toInt(), bitmap.getColor(2, 0))
        assertEquals(0xFF070809.toInt(), bitmap.getColor(2, 1))
    }

    /**
     * The array the pixels were installed from goes out of scope with the call,
     * so if the bitmap were pointing at it, a compaction is what moves what it
     * points at. Read once more after making the heap shuffle itself.
     */
    @Test
    fun `the bitmap outlives the array it was written from`() {
        val image = assertNotNull(argbImageBitmap(source, 3, 2))
        repeat(4) {
            System.gc()
            // Garbage to compact against: what a non-owning bitmap would be
            // left pointing at is the array this moved out of.
            Array(2_048) { ByteArray(4_096) { byte -> (byte / 3).toByte() } }
        }
        assertEquals(source.toList(), image.asSkiaBitmap().argbPixels()?.toList())
    }

    /**
     * The mesh backdrop draws its texture with one `drawImage` stretched over
     * the surface, so the bitmap this makes has to be drawable by Compose and
     * read by it the same way round. A quarter of the target is painted and the
     * rest is left alone, which is also the position check.
     */
    @Test
    fun `compose draws the bitmap it was given, where it was told to`() {
        val red = assertNotNull(argbImageBitmap(intArrayOf(0xFFFF0000.toInt()), 1, 1))
        val target = ImageBitmap(4, 2)
        Canvas(target).drawImageRect(
            image = red,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(1, 1),
            dstOffset = IntOffset(1, 0),
            dstSize = IntSize(2, 1),
            paint = Paint(),
        )
        val drawn = target.asSkiaBitmap()
        assertEquals(0xFFFF0000.toInt(), drawn.getColor(1, 0))
        assertEquals(0xFFFF0000.toInt(), drawn.getColor(2, 0))
        assertEquals(0, drawn.getColor(0, 0) ushr 24, "the dst offset moved")
        assertEquals(0, drawn.getColor(3, 1) ushr 24, "the dst size overspilled")
    }

    /**
     * The backdrop's seam trick is one draw of the texture's *first row* held
     * above the artwork, so srcOffset/srcSize have to pick the row they are
     * pointed at. Drawn one-to-one here: at equal src and dst size no resampler
     * has anything to blend, so an exact colour match says the row was chosen
     * rather than smeared.
     */
    @Test
    fun `a one-row src size draws that row and no other`() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val blue = 0xFF0000FF.toInt()
        val white = 0xFFFFFFFF.toInt()
        val texture = assertNotNull(argbImageBitmap(intArrayOf(red, green, blue, white), 2, 2))
        val target = ImageBitmap(2, 2)
        Canvas(target).drawImageRect(
            image = texture,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(2, 1),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(2, 1),
            paint = Paint(),
        )
        val drawn = target.asSkiaBitmap()
        assertEquals(red, drawn.getColor(0, 0))
        assertEquals(green, drawn.getColor(1, 0))
        assertEquals(0, drawn.getColor(0, 1) ushr 24, "the row below the seam came too")
        assertEquals(0, drawn.getColor(1, 1) ushr 24, "the row below the seam came too")
    }

    @Test
    fun `nothing comes back for an image that cannot exist`() {
        assertNull(argbImageBitmap(source, 0, 2))
        assertNull(argbImageBitmap(source, 2, -1))
        // Four across and two down wants eight pixels; there are six.
        assertNull(argbImageBitmap(source, 4, 2))
    }
}
