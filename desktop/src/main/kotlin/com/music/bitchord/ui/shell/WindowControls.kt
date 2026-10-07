package com.music.bitchord.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.FilterNone
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The window's own three buttons — minimize, maximize/restore, close — as a
 * permanent overlay at the frame's top-right edge.
 *
 * They left the toolbar because the full-screen player paints over the chrome:
 * buttons that lived in the toolbar vanished with it, and a window whose close
 * button comes and goes is a window nobody can trust. So the three sit in
 * their own layer, the last thing [Shell] paints, above player and chrome
 * alike — always in the same spot, always transparent until the pointer lands
 * on them.
 *
 * The one thing that does change is the ink: over the chrome the glyphs take
 * the theme's own foreground, over the player's artwork they go white, the
 * only colour that reads on an album cover. The hover fill is a plain
 * rectangle spanning the toolbar's full height — the shape the platform's own
 * buttons use — tinted by the same rule.
 */
@Composable
fun WindowControls(
    maximized: Boolean,
    /** True while the full-screen player is up — the glyphs go white over its artwork. */
    overPlayer: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (overPlayer) Color.White else MaterialTheme.colorScheme.onBackground
    Row(modifier = modifier.fillMaxHeight()) {
        ControlButton(Icons.Rounded.Remove, tint, onMinimize)
        ControlButton(
            if (maximized) Icons.Rounded.FilterNone else Icons.Rounded.CropSquare,
            tint,
            onToggleMaximize,
        )
        ControlButton(Icons.Rounded.Close, tint, onClose)
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .width(44.dp)
            .fillMaxHeight()
            .background(
                if (hovered) {
                    // The fill follows the ink, so a hover stays legible over
                    // whatever the layer beneath happens to be.
                    tint.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
    }
}
