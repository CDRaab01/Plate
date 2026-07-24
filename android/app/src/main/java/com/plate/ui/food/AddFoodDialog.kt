package com.plate.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.plate.data.remote.FoodOut
import com.plate.data.remote.PortionOut
import com.plate.util.UnitSystem

/**
 * Quantity + meal picker for a chosen food, built around [PortionPicker]: named household
 * portions when the food has them, a converting unit switch (never a value reset), fraction
 * presets + a stepper for count-like units, and an imperial/metric-aware default. [portions]
 * streams in from the detail request after the dialog opens — the dialog renders immediately
 * with serving/g/oz and the portion chips appear when loaded.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddFoodDialog(
    food: FoodOut,
    onDismiss: () -> Unit,
    onConfirm: (meal: String, args: LogArgs) -> Unit,
    portions: List<PortionOut> = emptyList(),
    unitSystem: UnitSystem = UnitSystem.IMPERIAL,
    initialMeal: String? = null,
    initialUnit: String? = null,
    initialQuantity: Double? = null,
    initialPortionGramWeight: Double? = null,
) {
    var meal by remember { mutableStateOf(initialMeal ?: "breakfast") }
    val picker = remember(food.id) {
        PortionPickerState(
            food = food,
            portions = portions,
            unitSystem = unitSystem,
            initialQuantity = initialQuantity,
            initialUnit = initialUnit,
            initialPortionGramWeight = initialPortionGramWeight,
        )
    }
    LaunchedEffect(portions) {
        if (portions.isNotEmpty()) picker.updatePortions(portions)
    }
    val args = picker.toLogArgs()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(food.name) },
        text = {
            Column {
                if (food.macrosIncomplete) {
                    Text(
                        "Some nutrition data is missing for this food — values may understate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text("Meal", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MEALS.forEach { m ->
                        FilterChip(
                            selected = meal == m,
                            onClick = { meal = m },
                            label = { Text(MEAL_LABELS[m] ?: m) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                PortionPicker(picker)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { args?.let { onConfirm(meal, it) } },
                enabled = args != null,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
