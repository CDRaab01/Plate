package com.plate.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Bottom action bar shown while foods are multi-selected: pick one meal, add them all at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BatchAddBar(
    count: Int,
    meal: String,
    onMealChange: (String) -> Unit,
    onAdd: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$count selected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) { Text("Clear") }
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MEALS.forEach { m ->
                    FilterChip(
                        selected = meal == m,
                        onClick = { onMealChange(m) },
                        label = { Text(MEAL_LABELS[m] ?: m) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Text("Add $count to ${MEAL_LABELS[meal] ?: meal}")
            }
        }
    }
}
