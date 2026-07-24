package com.plate.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.data.remote.FoodOut
import com.plate.data.remote.RecentFoodOut
import com.plate.data.repository.BatchLogItem
import com.plate.ui.diary.DiaryViewModel
import com.plate.util.UiState
import com.plate.util.UnitSystem

@Composable
fun FoodSearchScreen(
    onLogged: () -> Unit,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onPhoto: () -> Unit,
    onLabel: () -> Unit = {},
    onVoice: () -> Unit = {},
    onRestaurants: () -> Unit = {},
    searchViewModel: FoodSearchViewModel = hiltViewModel(),
    diaryViewModel: DiaryViewModel = hiltViewModel(),
) {
    val query by searchViewModel.query.collectAsState()
    val results by searchViewModel.results.collectAsState()
    val recent by searchViewModel.recent.collectAsState()
    val filter by searchViewModel.filter.collectAsState()
    val detail by searchViewModel.selectedDetail.collectAsState()
    val unitSystem by searchViewModel.unitSystem.collectAsState()
    var selected by remember { mutableStateOf<Selection?>(null) }

    // Multi-select add: food id → the chosen FoodOut (kept so we can compute each food's default
    // portion when the batch is committed). Non-empty ⇒ the screen is in selection mode.
    val batch = remember { mutableStateMapOf<String, FoodOut>() }
    var batchMeal by rememberSaveable { mutableStateOf("breakfast") }
    fun toggle(food: FoodOut) {
        if (batch.containsKey(food.id)) batch.remove(food.id) else batch[food.id] = food
    }

    selected?.let { sel ->
        // Portions ride on the food detail, fetched when the dialog opens (food tap — never a
        // keystroke). The dialog renders immediately; portion chips appear when the load lands.
        LaunchedEffect(sel.food.id) { searchViewModel.loadFoodDetail(sel.food.id) }
        val portions = (detail as? UiState.Success)?.data
            ?.takeIf { it.id == sel.food.id }
            ?.portions
            .orEmpty()
        AddFoodDialog(
            food = sel.food,
            portions = portions,
            unitSystem = unitSystem,
            initialMeal = sel.meal,
            initialUnit = sel.unit,
            initialQuantity = sel.quantity,
            initialPortionGramWeight = sel.portionGramWeight,
            onDismiss = { selected = null },
            onConfirm = { meal, args ->
                diaryViewModel.addEntry(sel.food.id, meal, args.quantity, args.unit, args.portionId)
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
        filter = filter,
        onFilterChange = searchViewModel::onFilterChange,
        onBack = onBack,
        onScan = onScan,
        onPhoto = onPhoto,
        onLabel = onLabel,
        onVoice = onVoice,
        onRestaurants = onRestaurants,
        // In selection mode a tap toggles selection; otherwise it opens the single-food dialog.
        onPick = { if (batch.isNotEmpty()) toggle(it) else selected = Selection(it) },
        onToggleSelect = { toggle(it) },
        onPickRecent = { r ->
            selected = Selection(
                food = r.food,
                meal = r.lastMeal,
                quantity = r.lastQuantity,
                unit = r.lastUnit,
                portionGramWeight = r.lastPortionGramWeight,
            )
        },
        selectedIds = batch.keys.toSet(),
        batchMeal = batchMeal,
        onBatchMealChange = { batchMeal = it },
        onClearSelection = { batch.clear() },
        onAddSelected = {
            val items = batch.values.map { food ->
                val (quantity, unit) = defaultPortion(food)
                BatchLogItem(food.id, quantity, unit)
            }
            diaryViewModel.addEntries(items, batchMeal) {
                batch.clear()
                onLogged()
            }
        },
    )
}

/** A chosen food plus the portion to pre-fill the dialog with (a recent re-log carries its last). */
private data class Selection(
    val food: FoodOut,
    val meal: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val portionGramWeight: Double? = null,
)

/** The portion the multi-select add uses for each food: one serving when the food defines one, else
 *  100 g — the same defaults the single-food dialog opens with. Portions are editable from the diary
 *  afterward, so the batch add stays a fast "add these" without a portion step per food. */
internal fun defaultPortion(food: FoodOut): Pair<Double, String> =
    if (food.kcalPerServing != null || food.servingSize != null) 1.0 to "serving" else 100.0 to "g"

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
    filter: SearchFilter = SearchFilter.ALL,
    onFilterChange: (SearchFilter) -> Unit = {},
    onLabel: () -> Unit = {},
    onVoice: () -> Unit = {},
    onRestaurants: () -> Unit = {},
    onToggleSelect: (FoodOut) -> Unit = {},
    selectedIds: Set<String> = emptySet(),
    batchMeal: String = "breakfast",
    onBatchMealChange: (String) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onAddSelected: () -> Unit = {},
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
                    IconButton(onClick = onRestaurants) {
                        Icon(
                            Icons.Outlined.Storefront,
                            contentDescription = "Restaurants",
                        )
                    }
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
        bottomBar = {
            if (selectedIds.isNotEmpty()) {
                BatchAddBar(
                    count = selectedIds.size,
                    meal = batchMeal,
                    onMealChange = onBatchMealChange,
                    onAdd = onAddSelected,
                    onClear = onClearSelection,
                )
            }
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
            if (query.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { onFilterChange(f) },
                            label = { Text(f.label) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SearchResults(
                results = results,
                recent = recent,
                onPick = onPick,
                onPickRecent = onPickRecent,
                onToggleSelect = onToggleSelect,
                selectedIds = selectedIds,
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
    onToggleSelect: (FoodOut) -> Unit,
    selectedIds: Set<String>,
) {
    val selectionMode = selectedIds.isNotEmpty()
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
                        FoodRow(
                            food = food,
                            selected = food.id in selectedIds,
                            selectionMode = selectionMode,
                            onClick = { onPick(food) },
                            onLongClick = { onToggleSelect(food) },
                        )
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
