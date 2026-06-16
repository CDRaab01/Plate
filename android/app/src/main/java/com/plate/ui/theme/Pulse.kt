package com.plate.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The PULSE semantic layer, adapted to Plate's nutrition domain: channel colors owned by the
 * macros, the structural colors the instrument-panel aesthetic is built from (hairline strokes,
 * panel tones, glow), and the two brand gradients.
 *
 * Plate mirrors Spotter's PULSE system but maps the channels to macros instead of workout
 * domains, so color still carries meaning for a tracker. The base hues are PULSE verbatim:
 *  - protein:  recovery green  — the build/repair macro
 *  - carbs:    effort blue     — the primary fuel/energy macro
 *  - fat:      streak orange    — the dense-energy macro
 *  - calories: strength violet — the headline aggregate
 *
 * Each channel has a `base` (strokes, text, rings), a `dim` (container fill — a pre-composited
 * solid, not an alpha, so hairlines drawn on top stay predictable) and an `on` (content atop the
 * base fill). Gradients are reserved for hero moments: [heroGradient] (blue → indigo) for the
 * greeting and primary CTAs is the signature; [energyGradient] (orange → amber) is the
 * celebration voice. Provided through [LocalPulse] by PlateTheme; pull via `PlateTheme.pulse`.
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
    val hairline: Color,        // 1px inner strokes on panels
    val hairlineStrong: Color,  // emphasized strokes (selected states)
    val panel: Color,
    val panelHigh: Color,
    val glow: Color,            // ring/dot glow base; draw at low alpha
    // Brand gradients
    val heroGradient: Brush,    // blue → indigo: greeting, primary CTAs
    val energyGradient: Brush,  // orange → amber: celebration moments
)

fun darkPulseColors() = PulseColors(
    protein = PulseGreen, proteinDim = Color(0xFF11332A), onProtein = Color(0xFF00301F),
    carbs = PulseBlue, carbsDim = Color(0xFF1B2440), onCarbs = Color(0xFFFFFFFF),
    fat = PulseOrange, fatDim = Color(0xFF3B2418), onFat = Color(0xFF2B1100),
    calories = PulseViolet, caloriesDim = Color(0xFF231F3F), onCalories = Color(0xFF120A38),
    hairline = Color(0x14FFFFFF),
    hairlineStrong = Color(0x29FFFFFF),
    panel = PulsePanel,
    panelHigh = PulsePanelHigh,
    glow = PulseBlue,
    heroGradient = Brush.linearGradient(listOf(PulseBlue, PulseIndigo)),
    energyGradient = Brush.linearGradient(listOf(PulseOrange, PulseAmber)),
)

fun lightPulseColors() = PulseColors(
    protein = PulseGreenDeep, proteinDim = Color(0xFFD8F3E8), onProtein = Color(0xFFFFFFFF),
    carbs = PulseBlueDeep, carbsDim = Color(0xFFDEE7FF), onCarbs = Color(0xFFFFFFFF),
    fat = PulseOrangeDeep, fatDim = Color(0xFFFBE3D4), onFat = Color(0xFFFFFFFF),
    calories = PulseVioletDeep, caloriesDim = Color(0xFFE6E2FB), onCalories = Color(0xFFFFFFFF),
    hairline = Color(0x1A000000),
    hairlineStrong = Color(0x33000000),
    panel = Color(0xFFFFFFFF),
    panelHigh = Color(0xFFF1F3F6),
    glow = PulseBlueDeep,
    heroGradient = Brush.linearGradient(listOf(PulseBlueDeep, PulseIndigoDeep)),
    energyGradient = Brush.linearGradient(listOf(Color(0xFFFF6B35), PulseAmber)),
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
