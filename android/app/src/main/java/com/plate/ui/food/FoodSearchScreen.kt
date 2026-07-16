package com.plate.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.ReceiptLong
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
import com.plate.data.remote.RecentFoodOut
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
    onScan: () -> Unit,
    onPhoto: () -> Unit,
    onLabel: () -> Unit = {},
    onVoice: () -> Unit = {},
    searchViewModel: FoodSearchViewModel = hiltViewModel(),
    diaryViewModel: DiaryViewModel = hiltViewModel(),
) {
    val query by searchViewModel.query.collectAsState()
    val results by searchViewModel.results.collectAsState()
    val recent by searchViewModel.recent.collectAsState()
    var selected by remember { mutableStateOf<Selection?>(null) }

    selected?.let { sel ->
        AddFoodDialog(
            food = sel.food,
            initialMeal = sel.meal,
            initialUnit = sel.unit,
            initialQuantity = sel.quantity,
            onDismiss = { selected = null },
            onConfirm = { meal, quantity, unit ->
                diaryViewModel.addEntry(sel.food.id, meal, quantity, unit)
                selected = null
                onLogged()
            },
        )
    }

    FoodSearchContent(
        query = query,
        onQueryChange = searchViewModel::onQueryChange,
        results = results,
        recent = recent,
        onBack = onBack,
        onScan = onScan,
        onPhoto = onPhoto,
        onLabel = onLabel,
        onVoice = onVoice,
        onPick = { selected = Selection(it) },
        onPickRecent = { r -> selected = Selection(r.food, r.lastMeal, r.lastQuantity, r.lastUnit) },
    )
}

/** A chosen food plus the portion to pre-fill the dialog with (a recent re-log carries its last). */
private data class Selection(
    val food: FoodOut,
    val meal: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
)

/** Stateless search body — rendered by [FoodSearchScreen] in the app and by screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodSearchContent(
    query: String,
    onQueryChange: (String) -> Unit,
    results: UiState<List<FoodOut>>,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onPhoto: () -> Unit,
    onPick: (FoodOut) -> Unit,
    recent: List<RecentFoodOut> = emptyList(),
    onPickRecent: (RecentFoodOut) -> Unit = {},
    onLabel: () -> Unit = {},
    onVoice: () -> Unit = {},
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
                actions = {
                    IconButton(onClick = onVoice) {
                        Icon(
                            Icons.Outlined.Mic,
                            contentDescription = "Log by voice",
                        )
                    }
                    IconButton(onClick = onLabel) {
                        Icon(
                            Icons.Outlined.ReceiptLong,
                            contentDescription = "Scan nutrition label",
                        )
                    }
                    IconButton(onClick = onPhoto) {
                        Icon(
                            Icons.Outlined.AddAPhoto,
                            contentDescription = "Log from photo",
                        )
                    }
                    IconButton(onClick = onScan) {
                        Icon(
                            Icons.Outlined.QrCodeScanner,
                            contentDescription = "Scan barcode",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Search foods") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            SearchResults(
                results = results,
                recent = recent,
                onPick = onPick,
                onPickRecent = onPickRecent,
            )
        }
    }
}

@Composable
private fun SearchResults(
    results: UiState<List<FoodOut>>,
    recent: List<RecentFoodOut>,
    onPick: (FoodOut) -> Unit,
    onPickRecent: (RecentFoodOut) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        when (results) {
            // Query empty: offer recent foods for one-tap re-log, else the search hint.
            is UiState.Idle -> if (recent.isNotEmpty()) {
                RecentFoods(recent = recent, onPick = onPickRecent)
            } else {
                Hint("Search for a food to log it")
            }
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

/** The "log again" surface shown while the search box is empty: tap a recent food to re-log it
 *  with its last portion pre-filled. */
@Composable
private fun RecentFoods(recent: List<RecentFoodOut>, onPick: (RecentFoodOut) -> Unit) {
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
                    "Last: ${formatPortion(r.lastQuantity, r.lastUnit)} · " +
                        (MEAL_LABELS[r.lastMeal] ?: r.lastMeal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatPortion(quantity: Double, unit: String): String {
    val q = if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
    val u = if (unit == "serving") (if (quantity == 1.0) "serving" else "servings") else unit
    return "$q $u"
}

@Composable
private fun FoodRow(food: FoodOut, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
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
    initialMeal: String? = null,
    initialUnit: String? = null,
    initialQuantity: Double? = null,
) {
    val hasServing = food.kcalPerServing != null || food.servingSize != null
    var unit by remember { mutableStateOf(initialUnit ?: if (hasServing) "serving" else "g") }
    var meal by remember { mutableStateOf(initialMeal ?: "breakfast") }
    var quantityText by remember {
        mutableStateOf(
            initialQuantity?.let {
                if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
            } ?: if (unit == "serving") "1" else "100",
        )
    }

    val quantity = quantityText.toDoubleOrNull()
    val estimatedKcal = quantity?.let { estimateKcal(food, it, unit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(food.name) },
        text = {
            Column {
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
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = {
                        Text(
                            when (unit) {
                                "serving" -> "Servings"
                                "oz" -> "Ounces"
                                else -> "Grams"
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                // Grams and ounces are always offered ("most food can be both"); serving too when defined.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasServing) {
                        FilterChip(
                            selected = unit == "serving",
                            onClick = { unit = "serving"; quantityText = "1" },
                            label = { Text("Serving") },
                        )
                    }
                    FilterChip(
                        selected = unit == "g",
                        onClick = { unit = "g"; quantityText = "100" },
                        label = { Text("Grams") },
                    )
                    FilterChip(
                        selected = unit == "oz",
                        onClick = { unit = "oz"; quantityText = "4" },
                        label = { Text("Ounces") },
                    )
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
    "oz" -> food.kcalPer100g * (com.plate.util.Units.ozToG(quantity) / 100.0)
    else -> food.kcalPer100g * (quantity / 100.0)
}
