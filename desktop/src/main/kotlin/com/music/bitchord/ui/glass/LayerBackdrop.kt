package com.music.bitchord.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * The thing a glass surface samples: one composable's recorded drawing.
 *
 * Upstream shape: Kyant0/backdrop's `LayerBackdrop`, vendored into the app's
 * `backdrop/` package. The recorder side keeps upstream's vendored change —
 * **record-then-blit** — so the page draws once per frame and the layer is
 * replayed, rather than being drawn twice.
 *
 * The desktop difference is coordinate plumbing: upstream asks Compose's
 * `localPositionOf` for the offset between the glass and the backdrop at draw
 * time; here the recorder publishes its root offset through
 * [onGloballyPositioned] and the glass node subtracts its own — the same
 * number, arrived at without LayoutCoordinator internals.
 */
class LayerBackdrop internal constructor(
    internal val layer: GraphicsLayer,
    internal val rootOffset: MutableState<Offset>,
)

@Composable
fun rememberAppBackdrop(): LayerBackdrop {
    val layer = rememberGraphicsLayer()
    val rootOffset = remember { mutableStateOf(Offset.Zero) }
    return remember(layer, rootOffset) { LayerBackdrop(layer, rootOffset) }
}

/** The app-wide backdrop a glass surface samples — upstream `LocalAppBackdrop`. */
val LocalAppBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** The settings switch relay — upstream `LocalLiquidGlassEnabled`, default off as upstream defaults off. */
val LocalLiquidGlassEnabled = compositionLocalOf { false }

/**
 * Records this node's drawing into [backdrop] for glass surfaces to sample.
 *
 * Attach it to the container whose content the glass should refract — on the
 * desktop that is the player's content root, whose artwork backdrop is what
 * the bottom controls' glass sits over.
 */
fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier = composed {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    this
        .onGloballyPositioned { backdrop.rootOffset.value = it.positionInRoot() }
        .drawWithContent {
            // Record at full size, then blit: the page's own pass stays single.
            backdrop.layer.record(
                density,
                layoutDirection,
                IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
            ) {
                this@drawWithContent.drawContent()
            }
            drawLayer(backdrop.layer)
        }
}

/** The root offset, read-only for consumers. */
internal val LayerBackdrop.rootPosition: Offset get() = rootOffset.value
