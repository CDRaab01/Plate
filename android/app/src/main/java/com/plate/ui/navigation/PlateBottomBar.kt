package com.plate.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import com.plate.ui.theme.PlateTheme

/**
 * The PULSE bottom navigation bar — Diary · Recipes · Summary · Coach. Each tab is tinted by a
 * macro channel so the bar reads as part of the instrument panel. Mirrors Spotter's PulseBottomBar.
 */
@Composable
fun PlateBottomBar(
    currentDestination: NavDestination?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    val pulse = PlateTheme.pulse
    val channels = listOf(pulse.carbs, pulse.protein, pulse.calories, pulse.fat)
    NavigationBar {
        TopLevelDestination.entries.forEachIndexed { index, dest ->
            val selected = currentDestination?.hierarchy?.any { it.route == dest.navRoute } == true
            val channel = channels[index % channels.size]
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(dest) },
                icon = { Icon(dest.icon, contentDescription = dest.label) },
                label = { Text(dest.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = channel,
                    selectedTextColor = channel,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
