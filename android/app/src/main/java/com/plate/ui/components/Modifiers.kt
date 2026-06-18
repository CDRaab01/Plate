package com.plate.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import com.plate.ui.theme.PulseMotion

/**
 * Tactile press feedback: the element springs down slightly while pressed. Pair with an
 * [interactionSource] that's also fed to the click handler so the scale tracks real presses.
 * Mirrors Spotter's PULSE `pressScale`.
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = PulseMotion.SpringPress,
        label = "pressScale",
    )
    this.scale(scale)
}
