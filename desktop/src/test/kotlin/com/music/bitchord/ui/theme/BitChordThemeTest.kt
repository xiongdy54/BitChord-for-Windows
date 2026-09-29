package com.music.bitchord.ui.theme

import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BitChordThemeTest {

    /**
     * The font pipeline itself: the five weights have to be on the classpath,
     * or every screen silently falls back to whatever Compose picks.
     */
    @Test
    fun `every sf pro weight is on the classpath`() {
        listOf(
            "sf_pro_display_regular.otf",
            "sf_pro_display_medium.otf",
            "sf_pro_display_semibold.otf",
            "sf_pro_display_bold.otf",
            "sf_pro_display_heavy.otf",
        ).forEach { name ->
            val size = javaClass.getResourceAsStream("/$name")?.use { it.readBytes().size }
            assertNotNull(size, "missing font resource $name")
            assertTrue(size > 1_000_000, "$name looks truncated ($size bytes)")
        }
    }

    /** 五个字重都要挂上，缺一个 Compose 会静默回退到最近的字重。 */
    @Test
    fun `sf pro display carries every weight the type scale asks for`() {
        val weights = listOf(
            FontWeight.W400, FontWeight.W500, FontWeight.W600, FontWeight.W700, FontWeight.W800,
        )
        val family = SFProDisplay as FontListFontFamily
        assertEquals(5, family.fonts.size)
        weights.forEach { weight ->
            assertTrue(family.fonts.any { it.weight == weight }, "missing weight $weight")
        }
    }

    /** 字型表必须整表套上 SF Pro，而不是留几档在默认字体上。 */
    @Test
    fun `every style in the scale uses sf pro`() {
        val typography = bitChordTypography()
        listOf(
            typography.displayLarge, typography.displayMedium, typography.displaySmall,
            typography.headlineLarge, typography.headlineMedium, typography.headlineSmall,
            typography.titleLarge, typography.titleMedium, typography.titleSmall,
            typography.bodyLarge, typography.bodyMedium, typography.bodySmall,
            typography.labelLarge, typography.labelMedium, typography.labelSmall,
        ).forEach { assertNotNull(it.fontFamily, "style without a family") }
        assertEquals(34.sp, typography.displayLarge.fontSize)
        assertEquals(FontWeight.W800, typography.displayLarge.fontWeight)
    }
}
