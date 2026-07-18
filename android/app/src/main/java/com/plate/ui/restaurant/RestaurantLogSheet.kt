package com.plate.ui.restaurant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.data.remote.RestaurantComponentOut
import com.plate.data.remote.RestaurantOut
import com.plate.ui.theme.PlateTheme
import com.plate.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.DataText
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader
import kotlin.math.roundToInt

private val MEALS = listOf("breakfast", "lunch", "dinner", "snack")

/**
 * The feature's heart: "I ate a Salsa Grille bowl" → tick what was in it. Category sections of
 * checkbox rows (pre-ticked from each component's default), a portion field per ticked row, a
 * running total, and a meal picker. Logging writes one diary entry per ticked component.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestaurantLogSheet(
    restaurantId: String,
    onDismiss: () -> Unit,
    onLogged: (String) -> Unit,
    viewModel: RestaurantLogViewModel = hiltViewModel(),
) {
    LaunchedEffect(restaurantId) { viewModel.load(restaurantId) }

    val state by viewModel.restaurant.collectAsState()
    val checked by viewModel.checked.collectAsState()
    val overrides by viewModel.overrides.collectAsState()
    val logState by viewModel.logState.collectAsState()

    LaunchedEffect(logState) {
        (logState as? UiState.Success)?.let { success ->
            viewModel.clearLogState()
            onLogged("Logged ${success.data} items")
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        when (val s = state) {
            is UiState.Success -> SheetContent(
                restaurant = s.data,
                checked = checked,
                overrides = overrides,
                totals = viewModel.totals(),
                logging = logState is UiState.Loading,
                error = (logState as? UiState.Error)?.message,
                onToggle = viewModel::toggle,
                onQuantity = viewModel::setQuantity,
                onLog = viewModel::log,
            )
            is UiState.Error -> Text(
                s.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )
            else -> Box(Modifier.fillMaxWidth().padding(32.dp)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun SheetContent(
    restaurant: RestaurantOut,
    checked: Set<String>,
    overrides: Map<String, Double>,
    totals: RunningTotals,
    logging: Boolean,
    error: String?,
    onToggle: (String) -> Unit,
    onQuantity: (String, Double) -> Unit,
    onLog: (String) -> Unit,
) {
    val grouped = restaurant.components.groupBy { it.category }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item {
            Text(restaurant.name, style = MaterialTheme.typography.titleLarge)
            Caption("Tick what was in it — nutrition is estimated from the linked foods.")
            Spacer(Modifier.height(4.dp))
        }
        grouped.forEach { (category, components) ->
            item(key = "header_$category") {
                SectionHeader(category, channel = PlateTheme.pulse.protein)
            }
            items(components.size, key = { components[it].id }) { index ->
                val component = components[index]
                ComponentRow(
                    component = component,
                    isChecked = component.id in checked,
                    quantity = overrides[component.id] ?: component.quantity,
                    onToggle = { onToggle(component.id) },
                    onQuantity = { onQuantity(component.id, it) },
                )
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DataText("${totals.kcal.roundToInt()}", color = PlateTheme.pulse.calories)
                Text(
                    " kcal · ${totals.proteinG.roundToInt()}P / " +
                        "${totals.carbsG.roundToInt()}C / ${totals.fatG.roundToInt()}F",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            LogToMealBar(enabled = !logging, onLog = onLog)
        }
    }
}

@Composable
private fun ComponentRow(
    component: RestaurantComponentOut,
    isChecked: Boolean,
    quantity: Double,
    onToggle: () -> Unit,
    onQuantity: (Double) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = isChecked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(component.name, style = MaterialTheme.typography.bodyLarge)
            val caption = when {
                component.foodName == null -> "not linked — edit the restaurant to fix"
                component.foodName != component.name -> "≈ ${component.foodName}"
                else -> null
            }
            val kcal = componentTotals(component, if (isChecked) quantity else null).kcal
            Caption(
                listOfNotNull(caption, "${kcal.roundToInt()} kcal").joinToString(" · "),
            )
        }
        if (isChecked) {
            OutlinedTextField(
                value = if (quantity % 1.0 == 0.0) quantity.toLong().toString()
                else quantity.toString(),
                onValueChange = { text -> text.toDoubleOrNull()?.let(onQuantity) },
                label = { Text(component.unit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
                modifier = Modifier.width(100.dp),
            )
        }
    }
}

@Composable
private fun LogToMealBar(enabled: Boolean, onLog: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        PulseButton(
            text = if (enabled) "Log to meal…" else "Logging…",
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MEALS.forEach { meal ->
                DropdownMenuItem(
                    text = { Text(meal.replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        expanded = false
                        onLog(meal)
                    },
                )
            }
        }
    }
}
