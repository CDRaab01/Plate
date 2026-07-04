package com.plate.ui.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.data.remote.FoodOut
import design.pulse.ui.components.Caption
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader
import com.plate.ui.theme.PlateTheme
import com.plate.util.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditScreen(
    recipeId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: RecipeEditViewModel = hiltViewModel(),
) {
    LaunchedEffect(recipeId) { if (recipeId != null) viewModel.loadExisting(recipeId) }

    val name by viewModel.name.collectAsState()
    val description by viewModel.description.collectAsState()
    val items by viewModel.items.collectAsState()
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val saveState by viewModel.saveState.collectAsState()

    LaunchedEffect(saveState) { if (saveState is UiState.Success) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (recipeId == null) "New recipe" else "Edit recipe") },
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
            OutlinedTextField(
                value = description,
                onValueChange = viewModel::setDescription,
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("Items", channel = PlateTheme.pulse.protein)
            if (items.isEmpty()) {
                Caption("No items yet — search below to add foods")
            }
            items.forEachIndexed { index, item ->
                ItemRow(
                    name = item.food.name,
                    quantity = item.quantity,
                    unit = item.unit,
                    onQuantity = { viewModel.setQuantity(index, it) },
                    onRemove = { viewModel.removeItem(index) },
                )
            }

            SectionHeader("Add foods", channel = PlateTheme.pulse.carbs)
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
                    SearchResultRow(food, onAdd = { viewModel.addItem(food) })
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
            ) { Text("Save recipe") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ItemRow(
    name: String,
    quantity: Double,
    unit: String,
    onQuantity: (Double) -> Unit,
    onRemove: () -> Unit,
) {
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = if (quantity % 1.0 == 0.0) quantity.toLong().toString() else quantity.toString(),
                onValueChange = { onQuantity(it.toDoubleOrNull() ?: 0.0) },
                label = { Text(unit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(110.dp),
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, contentDescription = "Remove $name")
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
                Caption("${food.kcalPer100g.toInt()} kcal / 100g" + (food.brand?.let { " · $it" } ?: ""))
            }
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Add ${food.name}")
            }
        }
    }
}
