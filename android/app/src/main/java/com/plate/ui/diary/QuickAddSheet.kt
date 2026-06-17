package com.plate.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val MEALS = listOf("breakfast", "lunch", "dinner", "snack")

/**
 * Quick add (Phase 8): log raw kcal/macros directly, with no source food. The user picks a meal,
 * an optional label, and the four macros. Calories are sent as typed (not derived) so this stays a
 * fast manual entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddDialog(
    initialMeal: String = "snack",
    onDismiss: () -> Unit,
    onSubmit: (meal: String, name: String?, kcal: Double, protein: Double, carbs: Double, fat: Double) -> Unit,
) {
    var meal by remember { mutableStateOf(initialMeal) }
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }

    val kcalValue = kcal.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick add") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MealPicker(meal = meal, onMeal = { meal = it })
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MacroField("Calories (kcal)", kcal) { kcal = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { MacroField("Protein", protein) { protein = it } }
                    Box(Modifier.weight(1f)) { MacroField("Carbs", carbs) { carbs = it } }
                    Box(Modifier.weight(1f)) { MacroField("Fat", fat) { fat = it } }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = kcalValue != null,
                onClick = {
                    onSubmit(
                        meal,
                        name.ifBlank { null },
                        kcalValue ?: 0.0,
                        protein.toDoubleOrNull() ?: 0.0,
                        carbs.toDoubleOrNull() ?: 0.0,
                        fat.toDoubleOrNull() ?: 0.0,
                    )
                    onDismiss()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MacroField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MealPicker(meal: String, onMeal: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("Meal: ${meal.replaceFirstChar { it.uppercase() }}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MEALS.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.replaceFirstChar { it.uppercase() }) },
                    onClick = { onMeal(m); expanded = false },
                )
            }
        }
    }
}
