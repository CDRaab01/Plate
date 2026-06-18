package com.plate.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.data.remote.DailyLog
import com.plate.data.remote.LogEntryOut
import com.plate.data.remote.MealGroup
import com.plate.data.remote.TotalsOut
import com.plate.ui.components.DataText
import com.plate.ui.components.PanelCard
import com.plate.ui.components.SectionHeader
import com.plate.ui.theme.PlateTheme
import com.plate.util.UiState
import kotlin.math.roundToInt

/** Human label for each meal bucket, in the order the backend returns them. */
private val MEAL_LABELS = mapOf(
    "breakfast" to "Breakfast",
    "lunch" to "Lunch",
    "dinner" to "Dinner",
    "snack" to "Snacks",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    onNavigateToSearch: () -> Unit,
    onNavigateToGoals: () -> Unit,
    onNavigateToAbout: () -> Unit,
    viewModel: DiaryViewModel = hiltViewModel(),
) {
    val state by viewModel.day.collectAsState()
    val greeting by viewModel.greeting.collectAsState()
    val mealNudge by viewModel.mealNudge.collectAsState()
    var showQuickAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today") },
                actions = {
                    IconButton(onClick = { showQuickAdd = true }) {
                        Icon(Icons.Outlined.Bolt, contentDescription = "Quick add")
                    }
                    IconButton(onClick = onNavigateToAbout) {
                        Icon(Icons.Outlined.Info, contentDescription = "About")
                    }
                    IconButton(onClick = onNavigateToGoals) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Goals")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToSearch) {
                Icon(Icons.Filled.Add, contentDescription = "Add food")
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UiState.Success -> DiaryContent(
                    day = s.data,
                    greeting = greeting,
                    mealNudge = mealNudge,
                    onDeleteEntry = viewModel::deleteEntry,
                )
                is UiState.Error -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    if (showQuickAdd) {
        QuickAddDialog(
            onDismiss = { showQuickAdd = false },
            onSubmit = { meal, name, kcal, protein, carbs, fat ->
                viewModel.quickAdd(meal, name, kcal, protein, carbs, fat)
            },
        )
    }
}

/** Stateless diary body — greeting panel, meal split with daily totals vs targets. */
@Composable
fun DiaryContent(
    day: DailyLog,
    greeting: String,
    mealNudge: String,
    onDeleteEntry: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item { GreetingPanel(greeting = greeting, mealNudge = mealNudge) }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            DailySummaryCard(
                totals = day.totals,
                targets = day.targets,
                trainedToday = day.trainedToday,
            )
        }
        item { Spacer(Modifier.height(16.dp)) }

        day.meals.forEach { group ->
            item { MealHeader(group) }
            if (group.entries.isEmpty()) {
                item {
                    Text(
                        "Nothing logged yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(group.entries, key = { it.id }) { entry ->
                    EntryRow(entry = entry, onDelete = { onDeleteEntry(entry.id) })
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun GreetingPanel(greeting: String, mealNudge: String) {
    val pulse = PlateTheme.pulse
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pulse.mealGradient)
            .padding(20.dp),
    ) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = mealNudge,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun DailySummaryCard(totals: TotalsOut, targets: TotalsOut, trainedToday: Boolean) {
    val pulse = PlateTheme.pulse
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            SectionHeader("Calories", channel = pulse.calories)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                DataText(
                    "${totals.kcal.roundToInt()}",
                    style = PlateTheme.dataType.dataLarge,
                    color = pulse.calories,
                )
                Text(
                    " / ${targets.kcal.roundToInt()} kcal",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (trainedToday) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.FitnessCenter,
                        contentDescription = null,
                        tint = pulse.protein,
                        modifier = Modifier.height(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Trained today · targets bumped",
                        style = MaterialTheme.typography.labelMedium,
                        color = pulse.protein,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val fraction = if (targets.kcal > 0) (totals.kcal / targets.kcal).toFloat() else 0f
            @Suppress("DEPRECATION")
            LinearProgressIndicator(
                progress = fraction.coerceIn(0f, 1f),
                color = pulse.calories,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MacroStat("Protein", totals.proteinG, targets.proteinG, pulse.protein)
                MacroStat("Carbs", totals.carbsG, targets.carbsG, pulse.carbs)
                MacroStat("Fat", totals.fatG, targets.fatG, pulse.fat)
            }
        }
    }
}

@Composable
private fun MacroStat(
    label: String,
    value: Double,
    target: Double,
    channel: androidx.compose.ui.graphics.Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = channel)
        Row(verticalAlignment = Alignment.Bottom) {
            DataText("${value.roundToInt()}", style = PlateTheme.dataType.numeralLarge)
            Text(
                " / ${target.roundToInt()} g",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MealHeader(group: MealGroup) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            MEAL_LABELS[group.meal] ?: group.meal.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "${group.totals.kcal.roundToInt()} kcal",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EntryRow(entry: LogEntryOut, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.foodName ?: "Food", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${formatQuantity(entry.quantity)} ${entry.unit} · ${entry.kcal.roundToInt()} kcal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete ${entry.foodName ?: "entry"}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Drop a trailing ``.0`` so whole quantities read "100 g" not "100.0 g". */
internal fun formatQuantity(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
