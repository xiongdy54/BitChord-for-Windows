package com.music.bitchord.ui.theme

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorMathTest {

    @Test
    fun `hsl round trip keeps a colour`() {
        listOf(0xFF3B7DD8.toInt(), 0xFFD84F3B.toInt(), 0xFF101010.toInt(), 0xFFF2F2F7.toInt()).forEach { argb ->
            val back = hslToColor(colorToHsl(argb))
            val r = (back.red * 255f).toInt()
            val g = (back.green * 255f).toInt()
            val b = (back.blue * 255f).toInt()
            assertTrue(abs(r - (argb shr 16 and 0xFF)) <= 1, "red drifted for $argb")
            assertTrue(abs(g - (argb shr 8 and 0xFF)) <= 1, "green drifted for $argb")
            assertTrue(abs(b - (argb and 0xFF)) <= 1, "blue drifted for $argb")
        }
    }

    /** WCAG 相对亮度：白 1.0、黑 0.0、中灰约 0.2159。 */
    @Test
    fun `relative luminance matches the wcag reference values`() {
        assertTrue(abs(relativeLuminance(0xFFFFFFFF.toInt()) - 1f) < 0.001f)
        assertTrue(abs(relativeLuminance(0xFF000000.toInt()) - 0f) < 0.001f)
        assertTrue(abs(relativeLuminance(0xFF808080.toInt()) - 0.2159f) < 0.01f)
    }

    /** 中性色不能被"提饱和"改造成红色——原版注释里踩过的坑。 */
    @Test
    fun `neutral stays neutral`() {
        assertEquals(0f, adaptedArtworkSaturation(0f, minimum = 0.20f, maximum = 0.62f))
        assertEquals(0.1f, adaptedArtworkSaturation(0.1f, minimum = 0.20f, maximum = 0.62f))
        // 有颜色但低于下限：抬到下限；高于上限：压到上限；区间内：原样。
        assertEquals(0.20f, adaptedArtworkSaturation(0.15f, minimum = 0.20f, maximum = 0.62f))
        assertEquals(0.30f, adaptedArtworkSaturation(0.30f, minimum = 0.20f, maximum = 0.62f))
        assertEquals(0.62f, adaptedArtworkSaturation(0.90f, minimum = 0.20f, maximum = 0.62f))
    }
}
