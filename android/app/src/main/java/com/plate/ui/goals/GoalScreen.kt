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
import com.plate.util.UnitSystem
import com.plate.util.Units
import kotlin.math.roundToInt

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
    val unitSystem by viewModel.unitSystem.collectAsState()

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
                    unitSystem = unitSystem,
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
    unitSystem: UnitSystem = UnitSystem.METRIC,
    onSave: (GoalUpsertRequest) -> Unit,
) {
    val imperial = unitSystem == UnitSystem.IMPERIAL
    val weightUnit = if (imperial) "lb" else "kg"
    val heightUnit = if (imperial) "in" else "cm"

    // Inputs are in the user's unit; the request to the server is always kg / cm / kg-per-week.
    var goalType by remember { mutableStateOf(existing?.goalType ?: "maintain") }
    var weight by remember(unitSystem) { mutableStateOf(existing?.weightKg?.let { display(kgToUnit(it, imperial)) } ?: "") }
    var height by remember(unitSystem) { mutableStateOf(existing?.heightCm?.let { display(cmToUnit(it, imperial)) } ?: "") }
    var age by remember { mutableStateOf(existing?.age?.toString() ?: "") }
    var sex by remember { mutableStateOf(existing?.sex ?: "male") }
    var activityLevel by remember { mutableStateOf(existing?.activityLevel ?: "moderate") }
    // Stored/displayed as a positive magnitude; the goal-type chip owns the direction (a cut is a
    // deficit, a bulk a surplus). We keep the sign off the field so "Cut" + a natural positive rate
    // can't read as a surplus — the server enforces the same rule as the source of truth.
    var rate by remember(unitSystem) {
        mutableStateOf(existing?.rateKgPerWeek?.let { display(rateKgToUnit(kotlin.math.abs(it), imperial)) } ?: "0.5")
    }

    val isSaving = saveState is UiState.Loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(start = 16.dp, top = 8.dp, end = 16.dp)
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
                label = "Weight ($weightUnit)",
                value = weight,
                onChange = { weight = it },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "Height ($heightUnit)",
                value = height,
                onChange = { height = it },
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(
                label = "Age",
                value = age,
                onChange = { age = it },
                modifier = if (goalType == "maintain") Modifier.fillMaxWidth() else Modifier.weight(1f),
                isDecimal = false,
            )
            // Maintain has no rate; for cut/bulk the goal chip owns the direction, so this is just
            // "how fast" as a positive number — no sign for the user to get wrong.
            if (goalType != "maintain") {
                NumberField(
                    label = "Rate ($weightUnit/week)",
                    value = rate,
                    onChange = { rate = it },
                    modifier = Modifier.weight(1f),
                    supportingText = if (goalType == "cut") "Weight to lose/week" else "Weight to gain/week",
                )
            }
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
                val w = weight.toDoubleOrNull() ?: return@PrimaryButtonFullWidth
                val h = height.toDoubleOrNull() ?: return@PrimaryButtonFullWidth
                val a = age.toIntOrNull() ?: return@PrimaryButtonFullWidth
                // Rate is entered as a positive magnitude; the goal type owns the direction. Send it
                // unsigned — the server derives the sign from goal_type (cut → deficit, bulk → surplus,
                // maintain → 0), so "Cut" can never come back as a surplus.
                val r = kotlin.math.abs(rate.toDoubleOrNull() ?: 0.0)
                // Convert the user's units back to the canonical kg / cm / kg-per-week the API expects.
                onSave(
                    GoalUpsertRequest(
                        goalType = goalType,
                        weightKg = unitToKg(w, imperial),
                        heightCm = unitToCm(h, imperial),
                        age = a,
                        sex = sex,
                        activityLevel = activityLevel,
                        rateKgPerWeek = unitToRateKg(r, imperial),
                    ),
                )
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

// Unit conversions between the user's display unit and the canonical metric the API stores. Height
// uses inches (1 in = 2.54 cm); weight + rate share the lb factor (rate is a linear delta).
private const val CM_PER_IN = 2.54
private fun kgToUnit(kg: Double, imperial: Boolean) = if (imperial) Units.kgToLb(kg) else kg
private fun unitToKg(v: Double, imperial: Boolean) = if (imperial) Units.lbToKg(v) else v
private fun cmToUnit(cm: Double, imperial: Boolean) = if (imperial) cm / CM_PER_IN else cm
private fun unitToCm(v: Double, imperial: Boolean) = if (imperial) v * CM_PER_IN else v
private fun rateKgToUnit(kg: Double, imperial: Boolean) = if (imperial) Units.kgToLb(kg) else kg
private fun unitToRateKg(v: Double, imperial: Boolean) = if (imperial) Units.lbToKg(v) else v

/** One-decimal display string, trimming a trailing ".0". */
private fun display(v: Double): String {
    val r = (v * 10).roundToInt() / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
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
