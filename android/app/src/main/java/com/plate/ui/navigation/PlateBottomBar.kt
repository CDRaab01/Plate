package com.plate.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import com.plate.ui.theme.PlateTheme

/**
 * The PULSE bottom navigation bar — Diary · Recipes · Summary · Coach. Each tab is tinted by a
 * macro channel so the bar reads as part of the instrument panel. Mirrors Spotter's PulseBottomBar:
 * flat panel background, 1dp hairline divider above, filled icon + dim pill on the active tab.
 */
@Composable
fun PlateBottomBar(
    currentDestination: NavDestination?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    val pulse = PlateTheme.pulse
    // One tint per tab: Diary · Recipes · Home (center) · Calendar · Coach.
    val channels = listOf(pulse.carbs, pulse.protein, pulse.calories, pulse.fat, pulse.carbs)
    val channelDims = listOf(pulse.carbsDim, pulse.proteinDim, pulse.caloriesDim, pulse.fatDim, pulse.carbsDim)
    Column {
        HorizontalDivider(thickness = 1.dp, color = pulse.hairline)
        NavigationBar(
            containerColor = pulse.panel,
            tonalElevation = 0.dp,
        ) {
            TopLevelDestination.entries.forEachIndexed { index, dest ->
                val selected = currentDestination?.hierarchy?.any { it.route == dest.navRoute } == true
                val channel = channels[index % channels.size]
                val channelDim = channelDims[index % channelDims.size]
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(dest) },
                    icon = {
                        Icon(
                            imageVector = if (selected) dest.selectedIcon else dest.icon,
                            contentDescription = dest.label,
                        )
                    },
                    label = { Text(dest.label, style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = channel,
                        selectedTextColor = channel,
                        indicatorColor = channelDim,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}
