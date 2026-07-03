package com.plate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.plate.ui.theme.PlateTheme

/** The brand mark: a plate silhouette over the PULSE hero gradient (emerald ramp). */
@Composable
fun BrandLogo(size: Dp = 76.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(PlateTheme.pulse.heroGradient),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.42f)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.92f)),
        )
    }
}

/**
 * The primary call-to-action used across the auth/goals flows. Now a thin alias over the PULSE
 * [PulseButton] (hero-gradient block with press-scale), so the whole app shares one button voice.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PulseButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled)
}

/** Full-width variant convenience. */
@Composable
fun PrimaryButtonFullWidth(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = PrimaryButton(text, onClick, modifier.fillMaxWidth(), enabled)
