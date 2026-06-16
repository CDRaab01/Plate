package com.plate.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * PULSE reference palette — shared verbatim with Spotter so the two apps read as one family.
 * Color always carries meaning: each hue is owned by a data domain (see Pulse.kt), and gradients
 * are reserved for hero moments. The Material 3 token sets below are derived from this palette.
 */

// Reference palette — neutral inks/panels + the channel hues and their WCAG-deep variants.
val PulseInk = Color(0xFF0B0D10)
val PulsePanel = Color(0xFF13161B)
val PulsePanelHigh = Color(0xFF1A1E25)
val PulseBlue = Color(0xFF4D7CFF)
val PulseIndigo = Color(0xFF7A45F0)
val PulseViolet = Color(0xFF8B7CFF)
val PulseOrange = Color(0xFFFF8A5C)
val PulseAmber = Color(0xFFF5A623)
val PulseGreen = Color(0xFF34D399)
val PulseRed = Color(0xFFFF5C5C)

// Deep variants — meet >= 4.5:1 contrast on white for the light theme.
val PulseBlueDeep = Color(0xFF2A5BFF)
val PulseIndigoDeep = Color(0xFF5B2BE0)
val PulseVioletDeep = Color(0xFF5B2BE0)
val PulseOrangeDeep = Color(0xFFC2410C)
val PulseGreenDeep = Color(0xFF047857)
val PulseRedDeep = Color(0xFFDC2626)

// Light theme — Material 3 named tokens (primary=blue, secondary=green, tertiary=violet).
val LightPrimary = Color(0xFF2A5BFF)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDEE7FF)
val LightOnPrimaryContainer = Color(0xFF0A2078)
val LightSecondary = Color(0xFF047857)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFD8F3E8)
val LightOnSecondaryContainer = Color(0xFF02382A)
val LightTertiary = Color(0xFF5B2BE0)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFE6E2FB)
val LightOnTertiaryContainer = Color(0xFF241C66)
val LightError = Color(0xFFDC2626)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFBE0E0)
val LightOnErrorContainer = Color(0xFF5C0E0E)
val LightBackground = Color(0xFFF4F6F8)
val LightOnBackground = Color(0xFF14181D)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF14181D)
val LightSurfaceVariant = Color(0xFFECEEF2)
val LightOnSurfaceVariant = Color(0xFF525A66)
val LightOutline = Color(0xFFC9CDD4)
val LightOutlineVariant = Color(0x1A000000)

// Dark theme — channel hues sit directly on the ink/panel surfaces.
val DarkPrimary = Color(0xFF4D7CFF)
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkPrimaryContainer = Color(0xFF1B2440)
val DarkOnPrimaryContainer = Color(0xFFD6E0FF)
val DarkSecondary = Color(0xFF34D399)
val DarkOnSecondary = Color(0xFF00301F)
val DarkSecondaryContainer = Color(0xFF11332A)
val DarkOnSecondaryContainer = Color(0xFFB9F2DC)
val DarkTertiary = Color(0xFF8B7CFF)
val DarkOnTertiary = Color(0xFF120A38)
val DarkTertiaryContainer = Color(0xFF231F3F)
val DarkOnTertiaryContainer = Color(0xFFDAD4FF)
val DarkError = Color(0xFFFF5C5C)
val DarkOnError = Color(0xFF3D0202)
val DarkErrorContainer = Color(0xFF4A1414)
val DarkOnErrorContainer = Color(0xFFFFD3D3)
val DarkBackground = Color(0xFF0B0D10)
val DarkOnBackground = Color(0xFFE7EAF0)
val DarkSurface = Color(0xFF13161B)
val DarkOnSurface = Color(0xFFE7EAF0)
val DarkSurfaceVariant = Color(0xFF1A1E25)
val DarkOnSurfaceVariant = Color(0xFF9AA3B2)
val DarkOutline = Color(0xFF2A2F38)
val DarkOutlineVariant = Color(0x14FFFFFF)
