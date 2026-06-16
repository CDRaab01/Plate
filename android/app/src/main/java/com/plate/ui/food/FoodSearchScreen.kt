package com.plate.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.data.remote.FoodOut
import com.plate.ui.diary.DiaryViewModel
import com.plate.util.UiState
import kotlin.math.roundToInt

private val MEALS = listOf("breakfast", "lunch", "dinner", "snack")
private val MEAL_LABELS = mapOf(
    "breakfast" to "Breakfast",
    "lunch" to "Lunch",
    "dinner" to "Dinner",
    "snack" to "Snacks",
)

@Composable
fun FoodSearchScreen(
    onLogged: () -> Unit,
    onBack: () -> Unit,
    searchViewModel: FoodSearchViewModel = hiltViewModel(),
    diaryViewModel: DiaryViewModel = hiltViewModel(),
) {
    val query by searchViewModel.query.collectAsState()
    val results by searchViewModel.results.collectAsState()
    var selected by remember { mutableStateOf<FoodOut?>(null) }

    selected?.let { food ->
        AddFoodDialog(
            food = food,
            onDismiss = { selected = null },
            onConfirm = { meal, quantity, unit ->
                diaryViewModel.addEntry(food.id, meal, quantity, unit)
                selected = null
                onLogged()
            },
        )
    }

    FoodSearchContent(
        query = query,
        onQueryChange = searchViewModel::onQueryChange,
        results = results,
        onBack = onBack,
        onPick = { selected = it },
    )
}

/** Stateless search body — rendered by [FoodSearchScreen] in the app and by screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodSearchContent(
    query: String,
    onQueryChange: (String) -> Unit,
    results: UiState<List<FoodOut>>,
    onBack: () -> Unit,
    onPick: (FoodOut) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add food") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Search foods") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            SearchResults(results = results, onPick = onPick)
        }
    }
}

@Composable
private fun SearchResults(results: UiState<List<FoodOut>>, onPick: (FoodOut) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        when (results) {
            is UiState.Idle -> Hint("Search for a food to log it")
            is UiState.Loading -> CircularProgressIndicator(Modifier.padding(top = 32.dp))
            is UiState.Error -> Text(
                results.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 32.dp),
            )
            is UiState.Success -> if (results.data.isEmpty()) {
                Hint("No matches — try a different search")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results.data, key = { it.id }) { food ->
                        FoodRow(food = food, onClick = { onPick(food) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 32.dp),
    )
}

@Composable
private fun FoodRow(food: FoodOut, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(food.name, style = MaterialTheme.typography.bodyLarge)
        val subtitle = buildString {
            food.brand?.let { append(it).append(" · ") }
            append("${food.kcalPer100g.roundToInt()} kcal / 100g")
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Quantity + meal picker for a chosen food. Shows a live kcal estimate so the user sees the
 * impact before logging. Defaults to a 100g portion (or one serving when the food defines one).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddFoodDialog(
    food: FoodOut,
    onDismiss: () -> Unit,
    onConfirm: (meal: String, quantity: Double, unit: String) -> Unit,
) {
    val hasServing = food.kcalPerServing != null || food.servingSize != null
    var unit by remember { mutableStateOf(if (hasServing) "serving" else "g") }
    var meal by remember { mutableStateOf("breakfast") }
    var quantityText by remember { mutableStateOf(if (unit == "serving") "1" else "100") }

    val quantity = quantityText.toDoubleOrNull()
    val estimatedKcal = quantity?.let { estimateKcal(food, it, unit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(food.name) },
        text = {
            Column {
                Text("Meal", style = MaterialTheme.typography.labelLarge)
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
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text(if (unit == "serving") "Servings" else "Grams") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasServing) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = unit == "serving",
                            onClick = { unit = "serving"; quantityText = "1" },
                            label = { Text("Serving") },
                        )
                        FilterChip(
                            selected = unit == "g",
                            onClick = { unit = "g"; quantityText = "100" },
                            label = { Text("Grams") },
                        )
                    }
                }
                estimatedKcal?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "≈ ${it.roundToInt()} kcal",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { quantity?.let { onConfirm(meal, it, unit) } },
                enabled = quantity != null && quantity > 0,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Client-side kcal preview only; the server computes and stores the authoritative snapshot. */
internal fun estimateKcal(food: FoodOut, quantity: Double, unit: String): Double = when (unit) {
    "serving" -> when {
        food.kcalPerServing != null -> food.kcalPerServing * quantity
        food.servingSize != null -> food.kcalPer100g * (quantity * food.servingSize / 100.0)
        else -> food.kcalPer100g * quantity
    }
    else -> food.kcalPer100g * (quantity / 100.0)
}
