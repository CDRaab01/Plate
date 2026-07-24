package com.plate.ui.food

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.plate.data.remote.FoodOut
import kotlin.math.roundToInt

/**
 * One search result row: name, then the most useful nutrition line we can build — per-serving
 * kcal with the human serving label when the food defines one ("140 kcal · 2 cookies"), the
 * kcal/100g basis otherwise — plus a source badge (USDA / OFF / Mine) and an "incomplete"
 * marker for foods whose source record was missing macros.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FoodRow(
    food: FoodOut,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Tap = add (or toggle in selection mode); long-press = start/adjust multi-select.
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(food.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                foodRowSubtitle(food),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        SourceBadge(source = food.source)
    }
}

/** Subtitle line: brand · per-serving kcal + serving label when available, else kcal/100g. */
internal fun foodRowSubtitle(food: FoodOut): String = buildString {
    food.brand?.let { append(it).append(" · ") }
    val servingKcal = food.kcalPerServing
        ?: food.servingSize?.let { food.kcalPer100g * it / 100.0 }
    if (servingKcal != null) {
        append("${servingKcal.roundToInt()} kcal · ")
        append(food.servingLabel ?: food.servingSize?.let { "${formatQuantity(it)} g" } ?: "1 serving")
    } else {
        append("${food.kcalPer100g.roundToInt()} kcal / 100g")
    }
    if (food.macrosIncomplete) append(" · incomplete")
}

/** Small origin tag on each result row so generic vs branded vs own foods read at a glance. */
@Composable
internal fun SourceBadge(source: String, modifier: Modifier = Modifier) {
    val label = when (source) {
        "usda" -> "USDA"
        "off" -> "OFF"
        "user" -> "Mine"
        else -> return
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
