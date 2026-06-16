package com.plate.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * PULSE type system. Three voices on a minor-third (1.2) UI scale — 12 / 14 / 17 / 20 / 24 / 29:
 *  - Space Grotesk for display/headline/title — geometric, technical, slightly engineered.
 *  - Inter for body/label — quiet and legible; labels run Medium+ with wide tracking and are
 *    used UPPERCASE as instrument-panel captions.
 *  - JetBrains Mono for data numerals (DataType.kt) — every figure aligns.
 *
 * TODO(phase-8): bundle the actual PULSE faces (Space Grotesk, Inter, JetBrains Mono — all OFL,
 * the same static-instance files Spotter ships under res/font) and point these families at them.
 * Until the binaries land, we fall back to the platform default sans + monospace so the type
 * SCALE matches PULSE exactly even though the faces don't yet. Bundling the fonts is a drop-in
 * change: only these three family definitions move from defaults to `FontFamily(Font(R.font.…))`.
 */

val SpaceGroteskFamily = FontFamily.Default
val InterFamily = FontFamily.Default
val JetBrainsMonoFamily = FontFamily.Monospace

val PlateTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.Bold,
        fontSize = 42.sp, lineHeight = 46.sp, letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.Bold,
        fontSize = 35.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.Bold,
        fontSize = 29.sp, lineHeight = 34.sp, letterSpacing = (-0.25).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.Bold,
        fontSize = 29.sp, lineHeight = 34.sp, letterSpacing = (-0.25).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.25).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Normal,
        fontSize = 17.sp, lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.sp,
    ),
)
