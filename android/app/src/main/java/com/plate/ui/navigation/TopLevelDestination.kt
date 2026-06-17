package com.plate.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Today
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The four bottom-bar destinations (Phase 8 PULSE shell). `navRoute` is what the tab navigates to
 * (the diary tab targets its nested graph); `leafRoute` is the screen route the bar is shown on.
 */
enum class TopLevelDestination(
    val navRoute: String,
    val leafRoute: String,
    val label: String,
    val icon: ImageVector,
) {
    DIARY(Routes.DIARY_GRAPH, Routes.DIARY, "Diary", Icons.Outlined.Today),
    RECIPES(Routes.RECIPES, Routes.RECIPES, "Recipes", Icons.Outlined.RestaurantMenu),
    SUMMARY(Routes.SUMMARY, Routes.SUMMARY, "Summary", Icons.Outlined.BarChart),
    COACH(Routes.COACH, Routes.COACH, "Coach", Icons.AutoMirrored.Outlined.Chat),
    ;

    companion object {
        /** Routes on which the bottom bar is visible (the top-level leaves only). */
        val barRoutes: Set<String> = entries.map { it.leafRoute }.toSet()
    }
}
