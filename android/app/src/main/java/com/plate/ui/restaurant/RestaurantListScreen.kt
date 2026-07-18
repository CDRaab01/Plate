package com.plate.ui.restaurant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.plate.data.remote.RestaurantOut
import com.plate.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.ErrorState
import design.pulse.ui.components.PanelCard

/**
 * Restaurants: chain checkbox templates. Tap a card to open the log sheet ("I ate a Salsa Grille
 * bowl" → tick what was in it); build new ones by hand or from a menu link (the FAB → editor),
 * or import a bundled chain preset in one tap. Shared templates from other accounts appear too —
 * loggable by anyone, editable only by their owner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestaurantListScreen(
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: RestaurantListViewModel = hiltViewModel(),
) {
    val state by viewModel.restaurants.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var logSheetId by remember { mutableStateOf<String?>(null) }
    var presetSheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    logSheetId?.let { id ->
        RestaurantLogSheet(
            restaurantId = id,
            onDismiss = { logSheetId = null },
            onLogged = { confirmation ->
                logSheetId = null
                viewModel.notify(confirmation) // surfaces via the shared snackbar effect above
            },
        )
    }

    if (presetSheetOpen) {
        ModalBottomSheet(onDismissRequest = { presetSheetOpen = false }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("Add a chain", style = MaterialTheme.typography.titleLarge)
                Caption("Curated from each chain's published nutrition — editable after adding.")
                presets.forEach { preset ->
                    PanelCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clickable {
                                presetSheetOpen = false
                                viewModel.importPreset(preset)
                            },
                    ) {
                        Column {
                            Text(preset.name, style = MaterialTheme.typography.titleMedium)
                            Caption("${preset.components.size} components · official nutrition")
                        }
                    }
                }
                if (presets.isEmpty()) {
                    Caption("No presets bundled in this build.")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restaurants") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { presetSheetOpen = true }) {
                        Icon(Icons.Outlined.LibraryAdd, contentDescription = "Add a chain preset")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = "New restaurant")
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UiState.Success -> RestaurantListContent(
                    restaurants = s.data,
                    onLog = { logSheetId = it },
                    onEdit = onEdit,
                    onDelete = viewModel::delete,
                )
                is UiState.Error -> ErrorState(
                    icon = Icons.Outlined.CloudOff,
                    title = "Couldn't load your restaurants",
                    detail = s.message,
                    onRetry = viewModel::load,
                )
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun RestaurantListContent(
    restaurants: List<RestaurantOut>,
    onLog: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (restaurants.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Storefront,
            title = "No restaurants yet",
            subtitle = "Add one from a menu link, build it yourself, or grab a chain preset.",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(restaurants, key = { it.id }) { restaurant ->
            RestaurantCard(
                restaurant = restaurant,
                onLog = { onLog(restaurant.id) },
                onEdit = { onEdit(restaurant.id) },
                onDelete = { onDelete(restaurant.id) },
            )
        }
    }
}

@Composable
private fun RestaurantCard(
    restaurant: RestaurantOut,
    onLog: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    PanelCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onLog)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(restaurant.name, style = MaterialTheme.typography.titleMedium)
                val ownership = if (restaurant.isOwner) {
                    if (restaurant.shared) "shared" else "private"
                } else {
                    "shared with you"
                }
                Caption("${restaurant.components.size} components · $ownership")
            }
            if (restaurant.isOwner) {
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "Edit ${restaurant.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete ${restaurant.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
