package com.plate.ui.recipe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.data.remote.DiscoveredRecipe
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.PanelCard
import com.plate.ui.theme.PlateTheme
import com.plate.util.UiState

private val MEALS = listOf("breakfast", "lunch", "dinner", "snack")
private val MEAL_LABELS = mapOf(
    "breakfast" to "Breakfast", "lunch" to "Lunch", "dinner" to "Dinner", "snack" to "Snack",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverRecipesScreen(
    onBack: () -> Unit,
    viewModel: DiscoverRecipesViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discover recipes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            DiscoverRecipesContent(
                query = query,
                onQueryChange = viewModel::setQuery,
                onSearch = viewModel::search,
                results = results,
                onPick = viewModel::import,
            )
            if (busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    val toLog = pending
    if (toLog != null) {
        AlertDialog(
            onDismissRequest = viewModel::keepPendingSaved,
            title = { Text("Saved \"${toLog.name}\"") },
            text = {
                Column {
                    Text("Add all its ingredients to a meal now?")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MEALS.forEach { meal ->
                            FilterChip(
                                selected = false,
                                onClick = { viewModel.logPendingTo(meal) },
                                label = { Text(MEAL_LABELS[meal] ?: meal) },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = viewModel::keepPendingSaved) { Text("Just save it") }
            },
        )
    }
}

@Composable
internal fun DiscoverRecipesContent(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    results: UiState<List<DiscoveredRecipe>>,
    onPick: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search recipes (e.g. high-protein chicken)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
        Spacer(Modifier.height(16.dp))
        when (val s = results) {
            is UiState.Idle -> EmptyState(
                icon = Icons.Outlined.TravelExplore,
                title = "Find a recipe",
                subtitle = "Search real recipes, save one, and log all its ingredients to a meal.",
            )
            is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is UiState.Error -> EmptyState(
                icon = Icons.Outlined.TravelExplore,
                title = "Couldn't search",
                subtitle = s.message,
            )
            is UiState.Success -> if (s.data.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.TravelExplore,
                    title = "No recipes found",
                    subtitle = "Try a different search.",
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(s.data, key = { it.sourceId }) { recipe ->
                        RecipeHit(recipe = recipe, onClick = { onPick(recipe.sourceId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeHit(recipe: DiscoveredRecipe, onClick: () -> Unit) {
    PanelCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column {
            Text(recipe.title, style = MaterialTheme.typography.titleMedium)
            val meta = buildList {
                recipe.servings?.let { add("$it servings") }
                recipe.readyInMinutes?.let { add("$it min") }
            }.joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Caption(meta, color = PlateTheme.pulse.protein)
            }
        }
    }
}
