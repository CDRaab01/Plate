package com.plate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import design.pulse.ui.theme.LocalDataTypography
import design.pulse.ui.theme.LocalSpacing
import design.pulse.ui.theme.PulseAccent
import design.pulse.ui.theme.PulseAmber
import design.pulse.ui.theme.PulseBlue
import design.pulse.ui.theme.PulseBlueDeep
import design.pulse.ui.theme.PulseDataTypography
import design.pulse.ui.theme.PulseGreen
import design.pulse.ui.theme.PulseGreenDeep
import design.pulse.ui.theme.PulseGreenDeeper
import design.pulse.ui.theme.PulseOrange
import design.pulse.ui.theme.PulseOrangeDeep
import design.pulse.ui.theme.PulsePanel
import design.pulse.ui.theme.PulsePanelHigh
import design.pulse.ui.theme.PulseTheme
import design.pulse.ui.theme.PulseViolet
import design.pulse.ui.theme.PulseVioletDeep
import design.pulse.ui.theme.Spacing

/**
 * Plate's semantic layer over the shared PULSE library (`design.pulse:pulse-ui`) — the nutrition
 * channel map. Generic tokens (palette, type, motion, shape, structure) and generic components now
 * come from the library; only Plate's domain meaning lives here.
 *
 * Plate leads GREEN (a food/nutrition app) via [PulseAccent.Green]; the channels map to macros:
 *  - protein:  recovery green  — build/repair macro (also the app's lead)
 *  - carbs:    effort blue     — primary fuel/energy macro
 *  - fat:      streak orange   — dense-energy macro
 *  - calories: strength violet — headline aggregate
 *
 * `base`/`dim`/`on` per channel; gradients reserved for hero moments — [heroGradient]/[mealGradient]
 * are the signature emerald ramp (green → forest), [energyGradient] the celebration voice. Provided
 * via [LocalPulse]; pull with `PlateTheme.pulse`. (Structure was collapsed into the M3 scheme +
 * these channels — the panel/hairline/glow values below match PULSE structure verbatim.)
 */
@Immutable
data class PulseColors(
    val protein: Color,
    val proteinDim: Color,
    val onProtein: Color,
    val carbs: Color,
    val carbsDim: Color,
    val onCarbs: Color,
    val fat: Color,
    val fatDim: Color,
    val onFat: Color,
    val calories: Color,
    val caloriesDim: Color,
    val onCalories: Color,
    // Structure
    val hairline: Color,
    val hairlineStrong: Color,
    val panel: Color,
    val panelHigh: Color,
    val glow: Color,
    // Brand gradients
    val heroGradient: Brush,   // emerald ramp (green → forest): primary CTAs (Plate leads green)
    val energyGradient: Brush, // orange → amber: celebration moments
    val mealGradient: Brush,   // emerald ramp (green → forest): greeting card (Plate-specific)
)

fun darkPulseColors() = PulseColors(
    protein = PulseGreen, proteinDim = Color(0xFF11332A), onProtein = Color(0xFF00301F),
    carbs = PulseBlue, carbsDim = Color(0xFF151C33), onCarbs = Color(0xFFFFFFFF),
    fat = PulseOrange, fatDim = Color(0xFF3B2418), onFat = Color(0xFF2B1100),
    calories = PulseViolet, caloriesDim = Color(0xFF231F3F), onCalories = Color(0xFF120A38),
    hairline = Color(0x14FFFFFF),
    hairlineStrong = Color(0x29FFFFFF),
    panel = PulsePanel,
    panelHigh = PulsePanelHigh,
    glow = PulseGreen,
    // Emerald ramp: a single-hue green gradient (medium -> forest) that varies luminance, not hue —
    // so it reads as one confident brand voice instead of the green->blue two-channel blend. Both
    // stops clear WCAG AA 4.5:1 for white text, so headline/nudge and hero-button labels stay legible.
    heroGradient = Brush.linearGradient(listOf(PulseGreenDeep, PulseGreenDeeper)),
    energyGradient = Brush.linearGradient(listOf(PulseOrange, PulseAmber)),
    mealGradient = Brush.linearGradient(listOf(PulseGreenDeep, PulseGreenDeeper)),
)

fun lightPulseColors() = PulseColors(
    protein = PulseGreenDeep, proteinDim = Color(0xFFD8F3E8), onProtein = Color(0xFFFFFFFF),
    carbs = PulseBlueDeep, carbsDim = Color(0xFFECF1FF), onCarbs = Color(0xFFFFFFFF),
    fat = PulseOrangeDeep, fatDim = Color(0xFFFBE3D4), onFat = Color(0xFFFFFFFF),
    calories = PulseVioletDeep, caloriesDim = Color(0xFFE6E2FB), onCalories = Color(0xFFFFFFFF),
    hairline = Color(0x1A000000),
    hairlineStrong = Color(0x33000000),
    panel = Color(0xFFFFFFFF),
    panelHigh = Color(0xFFF1F3F6),
    glow = PulseGreenDeep,
    heroGradient = Brush.linearGradient(listOf(PulseGreenDeep, PulseGreenDeeper)),
    energyGradient = Brush.linearGradient(listOf(Color(0xFFFF6B35), PulseAmber)),
    mealGradient = Brush.linearGradient(listOf(PulseGreenDeep, PulseGreenDeeper)),
)

val LocalPulse = staticCompositionLocalOf { darkPulseColors() }

/**
 * The channel for a macro by its canonical order — protein, carbs, fat, then calories, repeating.
 * Keeps macro readouts, legends and chart segments color-coded consistently across the app.
 */
fun PulseColors.macroChannel(macroIndex: Int): Color {
    val cycle = listOf(protein, carbs, fat, calories)
    return cycle[((macroIndex % cycle.size) + cycle.size) % cycle.size]
}

@Composable
fun PlateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    PulseTheme(darkTheme = darkTheme, accent = PulseAccent.Green) {
        // The library's Green M3 scheme matches Plate's verbatim EXCEPT the secondary family, which
        // Plate keeps blue (the carbs data voice). Reconcile just those four tokens; everything else
        // (primary green, tertiary violet, surfaces, outlines) is already identical.
        val reconciled = MaterialTheme.colorScheme.copy(
            secondary = if (darkTheme) PulseBlue else PulseBlueDeep,
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = if (darkTheme) Color(0xFF1B2440) else Color(0xFFDEE7FF),
            onSecondaryContainer = if (darkTheme) Color(0xFFD6E0FF) else Color(0xFF0A2078),
        )
        MaterialTheme(
            colorScheme = reconciled,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
        ) {
            CompositionLocalProvider(
                LocalPulse provides if (darkTheme) darkPulseColors() else lightPulseColors(),
            ) {
                content()
            }
        }
    }
}

/** Convenience accessors mirroring `MaterialTheme.*`. */
object PlateTheme {
    val pulse: PulseColors
        @Composable @ReadOnlyComposable get() = LocalPulse.current
    val dataType: PulseDataTypography
        @Composable @ReadOnlyComposable get() = LocalDataTypography.current
    val spacing: Spacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current
}
