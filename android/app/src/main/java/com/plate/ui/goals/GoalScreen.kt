package com.plate.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.data.remote.GoalOut
import com.plate.data.remote.GoalUpsertRequest
import com.plate.ui.components.PrimaryButtonFullWidth
import com.plate.util.UiState

private val GOAL_TYPES = listOf("maintain", "cut", "bulk")
private val GOAL_LABELS = mapOf("maintain" to "Maintain", "cut" to "Cut", "bulk" to "Bulk")
private val SEX_OPTIONS = listOf("male", "female")
private val ACTIVITY_LEVELS = listOf("sedentary", "light", "moderate", "active", "very_active")
private val ACTIVITY_LABELS = mapOf(
    "sedentary" to "Sedentary",
    "light" to "Light",
    "moderate" to "Moderate",
    "active" to "Active",
    "very_active" to "Very Active",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalScreen(
    onBack: () -> Unit,
    viewModel: GoalViewModel = hiltViewModel(),
) {
    val goalState by viewModel.goal.collectAsState()
    val saveState by viewModel.saveState.collectAsState()

    LaunchedEffect(saveState) {
        if (saveState is UiState.Success) {
            viewModel.clearSaveState()
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Goals") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = goalState) {
                is UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is UiState.Error -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                is UiState.Success -> GoalContent(
                    existing = s.data,
                    saveState = saveState,
                    onSave = viewModel::save,
                )
                else -> Unit
            }
        }
    }
}

/** Stateless form — pre-populated from the existing goal when one is set. */
@Composable
fun GoalContent(
    existing: GoalOut?,
    saveState: UiState<Unit>,
    onSave: (GoalUpsertRequest) -> Unit,
) {
    var goalType by remember { mutableStateOf(existing?.goalType ?: "maintain") }
    var weightKg by remember { mutableStateOf(existing?.weightKg?.toString() ?: "") }
    var heightCm by remember { mutableStateOf(existing?.heightCm?.toString() ?: "") }
    var age by remember { mutableStateOf(existing?.age?.toString() ?: "") }
    var sex by remember { mutableStateOf(existing?.sex ?: "male") }
    var activityLevel by remember { mutableStateOf(existing?.activityLevel ?: "moderate") }
    var rateKgPerWeek by remember { mutableStateOf(existing?.rateKgPerWeek?.toString() ?: "0") }

    val isSaving = saveState is UiState.Loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, top = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Your goal", style = MaterialTheme.typography.titleMedium)
        ChipRow(
            options = GOAL_TYPES,
            labels = GOAL_LABELS,
            selected = goalType,
            onSelect = { goalType = it },
        )

        Text("Sex", style = MaterialTheme.typography.titleMedium)
        ChipRow(
            options = SEX_OPTIONS,
            labels = mapOf("male" to "Male", "female" to "Female"),
            selected = sex,
            onSelect = { sex = it },
        )

        Text("Activity level", style = MaterialTheme.typography.titleMedium)
        ChipRow(
            options = ACTIVITY_LEVELS,
            labels = ACTIVITY_LABELS,
            selected = activityLevel,
            onSelect = { activityLevel = it },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(
                label = "Weight (kg)",
                value = weightKg,
                onChange = { weightKg = it },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "Height (cm)",
                value = heightCm,
                onChange = { heightCm = it },
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(
                label = "Age",
                value = age,
                onChange = { age = it },
                modifier = Modifier.weight(1f),
                isDecimal = false,
            )
            NumberField(
                label = "Rate (kg/week)",
                value = rateKgPerWeek,
                onChange = { rateKgPerWeek = it },
                modifier = Modifier.weight(1f),
                supportingText = "Negative to cut",
            )
        }

        if (saveState is UiState.Error) {
            Text(
                saveState.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        PrimaryButtonFullWidth(
            text = if (isSaving) "Saving…" else "Save goal",
            onClick = {
                val w = weightKg.toDoubleOrNull() ?: return@PrimaryButtonFullWidth
                val h = heightCm.toDoubleOrNull() ?: return@PrimaryButtonFullWidth
                val a = age.toIntOrNull() ?: return@PrimaryButtonFullWidth
                val r = rateKgPerWeek.toDoubleOrNull() ?: 0.0
                onSave(GoalUpsertRequest(goalType, w, h, a, sex, activityLevel, r))
            },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    options: List<String>,
    labels: Map<String, String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(labels[option] ?: option) },
            )
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isDecimal: Boolean = true,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = supportingText?.let { { Text(it) } },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isDecimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
    )
}
