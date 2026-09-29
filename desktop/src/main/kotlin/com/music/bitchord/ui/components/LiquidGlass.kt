package com.music.bitchord.ui.components

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

// Desktop stand-in for app/src/main/java/com/music/bitchord/ui/components/LiquidGlass.kt.
//
// The Android file is the glue over the RenderEffect/AGSL pipeline: it records
// the backdrop at a fraction of its resolution, chains colour controls, blur
// and a lens refraction, and hands the result to graphicsLayer. Compose Desktop
// exposes no RenderEffect attachment point, so that pipeline needs a Skia SkSL
// rewrite — its own slice, after this one.
//
// What lives here is the switch the ported bars already branch on, answered
// "no": every `LocalLiquidGlassEnabled.current && isGlassSupported()` reads
// false, so the screens take the non-glass path the Android app ships on
// API < 31 — the same bars, backed by Haze alone.

/** Whether the app has opted into glass. Upstream this is wired to a setting; here it stays off. */
val LocalLiquidGlassEnabled = staticCompositionLocalOf { false }

/**
 * The backdrop blur pipeline requires `android.graphics.RenderEffect` on a
 * `RenderNode`, available from Android 12 (API 31). A desktop window has no
 * such thing, so this is always false until the Skia rewrite lands.
 */
@Suppress("UNUSED_PARAMETER")
fun isGlassSupported(sdkInt: Int = 0): Boolean = false

/**
 * The call sites keep their shape; nothing is drawn. The glass branches that
 * call this are dead while [isGlassSupported] is false.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.liquidGlass(shape: CornerBasedShape): Modifier = this

/** The hairline a glass surface draws around itself. Carried over for the call sites; unused while glass is off. */
internal val GLASS_EDGE_WIDTH = 0.5.dp
internal val GLASS_EDGE_COLOR = Color.White.copy(alpha = 0.10f)

/**
 * Glass shows whatever is behind it rather than the theme's surface colour, so
 * the usual onSurface greys have nothing dependable to sit against. Pure black
 * or white off the theme's luminance is the only tint that holds against
 * arbitrary artwork.
 */
@Composable
fun glassContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.Black else Color.White
