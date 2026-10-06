package com.music.bitchord.ui.theme

import com.music.bitchord.data.settings.ThemeSetting
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The whole appearance switch in one table: the user's choice against what
 * the machine is doing. Two readers consume the same answer — the colour
 * scheme and the window's Mica backdrop — so the table is the contract
 * between them.
 */
class ResolveDarkThemeTest {

    @Test
    fun `system follows the machine`() {
        assertEquals(false, resolveDarkTheme(ThemeSetting.SYSTEM, systemDark = false))
        assertEquals(true, resolveDarkTheme(ThemeSetting.SYSTEM, systemDark = true))
    }

    @Test
    fun `an explicit choice overrides the machine`() {
        assertEquals(false, resolveDarkTheme(ThemeSetting.LIGHT, systemDark = true))
        assertEquals(true, resolveDarkTheme(ThemeSetting.DARK, systemDark = false))
    }
}
