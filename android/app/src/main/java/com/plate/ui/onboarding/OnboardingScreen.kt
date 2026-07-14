package com.plate.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import design.pulse.ui.components.OnboardingPage
import design.pulse.ui.components.OnboardingScaffold
import design.pulse.ui.theme.Pulse

/**
 * First-run onboarding — three pages teaching Plate's core loop (log → see what's left → targets that
 * learn), built on Pulse's [OnboardingScaffold]. Presentation only; the "seen" flag lives in
 * AppPreferences and the gate is in MainActivity. [onFinish] persists the flag and drops into the app.
 */
private data class OnboardPage(val icon: ImageVector, val title: String, val body: String)

private val pages = listOf(
    OnboardPage(
        Icons.Outlined.RestaurantMenu,
        "Log it in seconds",
        "Search a food, scan a barcode, or snap a photo of your plate — Plate does the math.",
    ),
    OnboardPage(
        Icons.Outlined.DonutLarge,
        "See what's left",
        "Your calorie ring and remaining protein, carbs, and fat update live as you log.",
    ),
    OnboardPage(
        Icons.Outlined.Insights,
        "Targets that learn",
        "Plate tunes your goals to your real metabolism and coaches you through the week.",
    ),
)

@Composable
fun PlateOnboarding(onFinish: () -> Unit) {
    OnboardingScaffold(
        pageCount = pages.size,
        onFinish = onFinish,
        finishLabel = "Get started",
    ) { index ->
        val page = pages[index]
        OnboardingPage(
            title = page.title,
            body = page.body,
            illustration = { OnboardGlyph(page.icon) },
        )
    }
}

@Composable
private fun OnboardGlyph(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .background(Pulse.accent.dim, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Pulse.accent.base,
            modifier = Modifier.size(44.dp),
        )
    }
}
