package com.music.bitchord.ui.theme

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArtworkQuantiserTest {

    private fun solid(width: Int, height: Int, argb: Int) = IntArray(width * height) { argb }

    /** 七成红三成蓝：dominant 必须按人口选红，而不是被更饱和的蓝带走。 */
    @Test
    fun `dominant follows population, not saturation`() {
        val pixels = IntArray(100).also { px ->
            for (i in 0 until 70) px[i] = 0xFFC02020.toInt()
            for (i in 70 until 100) px[i] = 0xFF2030FF.toInt()
        }
        val seed = seedOf(pixels, width = 10, height = 10)!!
        assertTrue(
            seed.dominant.red > seed.dominant.blue,
            "dominant should be the red majority, was ${seed.dominant}",
        )
    }

    /** 两个颜色都得留在色块表里——量化器不能把少数色丢了。 */
    @Test
    fun `quantiser keeps both colours of a two-tone image`() {
        val pixels = IntArray(100).also { px ->
            for (i in 0 until 70) px[i] = 0xFFC02020.toInt()
            for (i in 70 until 100) px[i] = 0xFF2030FF.toInt()
        }
        val swatches = medianCutSwatches(pixels, maxColors = 24)
        assertTrue(swatches.size >= 2, "expected at least two swatches, got ${swatches.size}")
        assertTrue(swatches.any { (it.rgb shr 16 and 0xFF) > (it.rgb and 0xFF) }, "the red half is missing")
        assertTrue(swatches.any { (it.rgb and 0xFF) > (it.rgb shr 16 and 0xFF) }, "the blue half is missing")
    }

    /** 单色图的 edge 就是它自己的颜色（底部 18% 的均值）。 */
    @Test
    fun `edge colour averages the bottom band`() {
        val seed = seedOf(solid(20, 20, 0xFF445566.toInt()), width = 20, height = 20)!!
        listOf(seed.edge.red, seed.edge.green, seed.edge.blue).forEach {
            assertTrue(it > 0.20f && it < 0.45f, "unexpected edge component $it")
        }
    }

    /** 顶带亮度：全白图接近 1，全黑图接近 0。 */
    @Test
    fun `top band luminance separates light from dark artwork`() {
        assertTrue(seedOf(solid(20, 20, 0xFFFFFFFF.toInt()), 20, 20)!!.topBandLuminance > 0.95f)
        assertTrue(seedOf(solid(20, 20, 0xFF000000.toInt()), 20, 20)!!.topBandLuminance < 0.05f)
    }

    /** 全黑封面依然要给出一套色板（页面会走深色变体），不能返回 null。 */
    @Test
    fun `a black sleeve still yields a seed`() {
        assertNotNull(seedOf(solid(8, 8, 0xFF000000.toInt()), 8, 8))
    }
}
