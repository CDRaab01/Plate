package com.plate.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Today
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The five bottom-bar destinations (PULSE shell). `navRoute` is what the tab navigates to
 * (the diary tab targets its nested graph); `leafRoute` is the screen route the bar is shown on.
 * `icon` is shown when the tab is unselected; `selectedIcon` (filled) when active.
 */
enum class TopLevelDestination(
    val navRoute: String,
    val leafRoute: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    DIARY(Routes.DIARY_GRAPH, Routes.DIARY, "Diary", Icons.Outlined.Today, Icons.Filled.Today),
    RECIPES(Routes.RECIPES, Routes.RECIPES, "Recipes", Icons.Outlined.RestaurantMenu, Icons.Filled.RestaurantMenu),
    HOME(Routes.HOME, Routes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    SUMMARY(Routes.SUMMARY, Routes.SUMMARY, "Calendar", Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    COACH(Routes.COACH, Routes.COACH, "Coach", Icons.AutoMirrored.Outlined.Chat, Icons.AutoMirrored.Filled.Chat),
    ;

    companion object {
        /** Routes on which the bottom bar is visible (the top-level leaves only). */
        val barRoutes: Set<String> = entries.map { it.leafRoute }.toSet()
    }
}
