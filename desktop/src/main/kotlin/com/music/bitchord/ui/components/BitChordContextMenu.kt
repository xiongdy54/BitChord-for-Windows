package com.music.bitchord.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * The app's own right-click menu, replacing the platform-default renderer the
 * foundation menus ship with — a bare white list that landed on these pages
 * looking like a Swing leftover.
 *
 * The house style, read off the interaction playbook the UI elsewhere
 * follows: a 10dp-rounded surface floating on a shadow (a menu reads as
 * *above* the page, and shadow is the secondary action to the pop's
 * primary), rows at the app's own rhythm with a hover wash that eases in
 * over 120ms — hover is anticipation, it must be quicker than a blink — and
 * an opening fade-and-settle at 140ms so the menu arrives rather than
 * appears. The close is instant: leaving fast is what makes a menu feel
 * light.
 *
 * Installed once, in `Main`, over the theme, so the colours follow dark and
 * light like every other surface.
 */
class BitChordContextMenuRepresentation : ContextMenuRepresentation {

    @Composable
    override fun Representation(
        state: ContextMenuState,
        itemsProvider: () -> List<ContextMenuItem>,
    ) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        val items = itemsProvider()
        if (items.isEmpty()) return

        // The popup sits where the pointer opened the menu — the state's own
        // rect, window-relative — pulled inside the window once the menu has
        // measured. The provider reads the rect through a holder because the
        // platform calls it during layout, after composition has set it.
        val anchor = remember { AnchorRectHolder() }
        anchor.rect = status.rect

        // The entrance: transparent and a touch small on the first frame,
        // easing to rest. It runs once per open, because the popup's
        // composition is fresh each time.
        var settled by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { settled = true }
        val appearance by animateFloatAsState(
            targetValue = if (settled) 1f else 0f,
            animationSpec = tween(MENU_OPEN_MS, easing = MENU_EASE_OUT),
            label = "menuAppearance",
        )

        Popup(
            popupPositionProvider = remember { MenuPopupPositionProvider(anchor) },
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
            properties = PopupProperties(focusable = true),
        ) {
            BitChordMenuPanel(
                items = items,
                onItem = { item ->
                    state.status = ContextMenuState.Status.Closed
                    item.onClick()
                },
                modifier = Modifier.graphicsLayer {
                    alpha = appearance
                    val scale = 0.96f + 0.04f * appearance
                    scaleX = scale
                    scaleY = scale
                    // Growing from the pointer's corner, not the centre: the
                    // menu extends from where the click happened.
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            )
        }
    }
}

/**
 * The menu's visible self — the rounded surface, its shadow and border, and
 * the rows with their hover washes. Split from [Representation] so the
 * scripted screenshot pass can render the exact same panel in place
 * (`bitchord.menuPreview`): the popup lives in its own native window, which
 * the window-shot pipeline cannot see.
 */
@Composable
fun BitChordMenuPanel(
    items: List<ContextMenuItem>,
    onItem: (ContextMenuItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MENU_SHAPE,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .width(IntrinsicSize.Max)
                .padding(vertical = 5.dp),
        ) {
            items.forEach { item ->
                MenuRow(item) { onItem(item) }
            }
        }
    }
}

/** The click's window-relative rect, handed over one frame at a time. */
private class AnchorRectHolder {
    var rect: Rect = Rect.Zero
}

/**
 * Anchors the popup at the open rect's top-left, clamped inside the window
 * so a menu near an edge stays whole.
 */
private class MenuPopupPositionProvider(
    private val holder: AnchorRectHolder,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (holder.rect.left.toInt() - anchorBounds.left)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (holder.rect.top.toInt() - anchorBounds.top)
            .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

@Composable
private fun MenuRow(item: ContextMenuItem, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Hover eases in fast (anticipation) and out a touch slower; a disabled
    // row takes no hover at all — a control that cannot act does not pretend
    // to listen.
    val hoverFill = animateRowHover(enabled = item.enabled, hovered = hovered)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MENU_ROW_HEIGHT)
            .let { if (item.enabled) it.hoverable(interaction) else it }
            .background(hoverFill)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = item.enabled,
                onClick = onClick,
            )
            .padding(horizontal = MENU_ROW_GAP, vertical = 7.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.W500,
            ),
            color = if (item.enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            },
            maxLines = 1,
            modifier = Modifier.padding(end = 24.dp),
        )
    }
}

/** The row's hover wash, at the timing the hover playbook asks for. */
@Composable
private fun animateRowHover(enabled: Boolean, hovered: Boolean): Color {
    val target = when {
        !enabled -> Color.Transparent
        hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    return animateColorAsState(
        targetValue = target,
        animationSpec = tween(if (hovered) 120 else 180),
        label = "menuRowHover",
    ).value
}

private val MENU_SHAPE = RoundedCornerShape(10.dp)
private val MENU_ROW_HEIGHT = 30.dp
private val MENU_ROW_GAP = 14.dp
private const val MENU_OPEN_MS = 140

/** Fast-out for the menu's entrance — the app's own easing, one place. */
private val MENU_EASE_OUT = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.2f, 1f)
