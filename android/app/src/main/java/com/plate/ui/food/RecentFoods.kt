package com.plate.ui.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.plate.data.remote.RecentFoodOut

/** The "log again" surface shown while the search box is empty: tap a recent food to re-log it
 *  with its last portion pre-filled. */
@Composable
internal fun RecentFoods(recent: List<RecentFoodOut>, onPick: (RecentFoodOut) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "RECENT",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
        items(recent, key = { it.food.id }) { r ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(r) }
                    .padding(vertical = 12.dp),
            ) {
                Text(r.food.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Last: ${formatLastPortion(r.lastQuantity, r.lastUnit)} · " +
                        (MEAL_LABELS[r.lastMeal] ?: r.lastMeal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun formatLastPortion(quantity: Double, unit: String): String {
    val q = formatQuantity(quantity)
    val u = if (unit == "serving") (if (quantity == 1.0) "serving" else "servings") else unit
    return "$q $u"
}
