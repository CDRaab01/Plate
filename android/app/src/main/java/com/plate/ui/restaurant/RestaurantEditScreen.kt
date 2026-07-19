package com.plate.ui.restaurant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.data.remote.FoodOut
import com.plate.ui.theme.PlateTheme
import com.plate.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader
import kotlin.math.roundToInt

/**
 * Build or edit a restaurant template: paste a menu link and parse it into component drafts
 * (official numbers kept verbatim, generics resolved to estimates), or add components by hand
 * via the embedded food search — the recipe-editor pattern. Nothing hits the server until Save.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RestaurantEditScreen(
    restaurantId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: RestaurantEditViewModel = hiltViewModel(),
) {
    LaunchedEffect(restaurantId) { if (restaurantId != null) viewModel.loadExisting(restaurantId) }

    val name by viewModel.name.collectAsState()
    val menuUrl by viewModel.menuUrl.collectAsState()
    val menuText by viewModel.menuText.collectAsState()
    val shared by viewModel.shared.collectAsState()
    val components by viewModel.components.collectAsState()
    val newCategory by viewModel.newCategory.collectAsState()
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val parseState by viewModel.parseState.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val presetSuggestion by viewModel.presetSuggestion.collectAsState()

    LaunchedEffect(saveState) { if (saveState is UiState.Success) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (restaurantId == null) "New restaurant" else "Edit restaurant") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = viewModel::setName,
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            presetSuggestion?.let { preset ->
                Button(
                    onClick = { viewModel.applyPreset(preset) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Use the ${preset.name} preset (${preset.components.size} items)")
                }
                Caption("Prefilled from ${preset.name}'s official menu — edit anything before saving.")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Shared", style = MaterialTheme.typography.bodyLarge)
                    Caption("Everyone on your server can see and log from it")
                }
                Switch(checked = shared, onCheckedChange = viewModel::setShared)
            }

            SectionHeader("From a menu link", channel = PlateTheme.pulse.carbs)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = menuUrl,
                    onValueChange = viewModel::setMenuUrl,
                    label = { Text("Menu web address") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = viewModel::parseMenu,
                    enabled = parseState !is UiState.Loading,
                ) { Text("Parse") }
            }

            SectionHeader("Or paste the menu / nutrition", channel = PlateTheme.pulse.carbs)
            Caption("Copy a chain's nutrition table (or menu) and paste it here — works when a link won't load.")
            OutlinedTextField(
                value = menuText,
                onValueChange = viewModel::setMenuText,
                label = { Text("Paste menu or nutrition text") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = viewModel::parseText,
                enabled = parseState !is UiState.Loading && menuText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Parse pasted text") }

            when (val p = parseState) {
                is UiState.Loading -> Caption("Reading the menu…")
                is UiState.Success -> p.data?.let { Caption(it) }
                is UiState.Error -> Text(p.message, color = MaterialTheme.colorScheme.error)
                else -> {}
            }

            SectionHeader("Components", channel = PlateTheme.pulse.protein)
            if (components.isEmpty()) {
                Caption("Nothing yet — parse the menu link above, or add components below.")
            }
            val indexed = components.withIndex().toList()
            groupByCategory(components).forEach { (category, drafts) ->
                Caption(category)
                drafts.forEach { draft ->
                    val index = indexed.first { it.value === draft }.index
                    ComponentEditRow(
                        draft = draft,
                        onQuantity = { viewModel.setQuantity(index, it) },
                        onDefaultChecked = { viewModel.setDefaultChecked(index, it) },
                        onRemove = { viewModel.removeComponent(index) },
                    )
                }
            }

            SectionHeader("Add a component", channel = PlateTheme.pulse.carbs)
            OutlinedTextField(
                value = newCategory,
                onValueChange = viewModel::setNewCategory,
                label = { Text("Category (e.g. Protein, Rice, Toppings)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val existingCategories = components.map { it.category }.distinct()
            if (existingCategories.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    existingCategories.forEach { category ->
                        FilterChip(
                            selected = newCategory == category,
                            onClick = { viewModel.setNewCategory(category) },
                            label = { Text(category) },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    label = { Text("Search foods") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = viewModel::search) { Text("Search") }
            }
            when (val r = results) {
                is UiState.Loading -> CircularProgressIndicator(Modifier.padding(8.dp))
                is UiState.Error -> Text(r.message, color = MaterialTheme.colorScheme.error)
                is UiState.Success -> r.data.forEach { food ->
                    SearchResultRow(food, onAdd = { viewModel.addComponent(food) })
                }
                else -> {}
            }

            (saveState as? UiState.Error)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = viewModel::save,
                enabled = saveState !is UiState.Loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Save restaurant") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ComponentEditRow(
    draft: ComponentDraft,
    onQuantity: (Double) -> Unit,
    onDefaultChecked: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(draft.name, style = MaterialTheme.typography.bodyLarge)
                    val caption = when {
                        draft.macros != null ->
                            "official — ${draft.macros.kcal.roundToInt()} kcal" +
                                (draft.macros.servingDesc?.let { " / $it" } ?: "")
                        draft.foodId == null -> "not linked — tap a search result to replace"
                        draft.foodName != null && draft.foodName != draft.name ->
                            "≈ ${draft.foodName}"
                        else -> null
                    }
                    caption?.let { Caption(it) }
                }
                OutlinedTextField(
                    value = if (draft.quantity % 1.0 == 0.0) draft.quantity.toLong().toString()
                    else draft.quantity.toString(),
                    onValueChange = { text -> text.toDoubleOrNull()?.let(onQuantity) },
                    label = { Text(draft.unit) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(100.dp),
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove ${draft.name}")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = draft.defaultChecked, onCheckedChange = onDefaultChecked)
                Caption("Pre-ticked when logging")
            }
        }
    }
}

@Composable
private fun SearchResultRow(food: FoodOut, onAdd: () -> Unit) {
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(food.name, style = MaterialTheme.typography.bodyLarge)
                Caption(
                    "${food.kcalPer100g.toInt()} kcal / 100g" +
                        (food.brand?.let { " · $it" } ?: ""),
                )
            }
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Add ${food.name}")
            }
        }
    }
}
