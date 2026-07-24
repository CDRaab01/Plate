package com.plate.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.plate.data.remote.FoodOut
import com.plate.data.remote.PortionOut
import com.plate.util.UnitSystem
import com.plate.util.Units
import kotlin.math.abs
import kotlin.math.roundToInt

/** The unit the picker is currently in: a named household portion, or one of the plain bases. */
sealed interface PickerUnit {
    data object Serving : PickerUnit
    data object Gram : PickerUnit
    data object Oz : PickerUnit
    data class Named(val portion: PortionOut) : PickerUnit
}

/** What gets sent to the log endpoint for the current picker state. */
data class LogArgs(
    val quantity: Double,
    val unit: String,
    val portionId: String? = null,
)

/** Quantity steps the +/− stepper moves by and the smallest loggable fraction. */
private const val MIN_QUANTITY = 0.25

/** Fraction presets offered for serving/named-portion quantities. */
internal val QUANTITY_PRESETS = listOf(0.25, 0.5, 1.0, 1.5, 2.0)

/**
 * State for the portion picker — a plain class (JVM unit-testable) holding Compose state.
 *
 * The core rule: **grams are the single source of truth**. Switching units *converts* the
 * current amount instead of resetting it (1 × "1 cup" (240 g) → Ounces shows 8.47, → Grams
 * shows 240), fixing the old dialog's hard-reset-on-switch. Named portions stream in after the
 * dialog opens (the detail request); [updatePortions] adds their chips without disturbing what
 * the user already chose, and restores a recent re-log's named portion when it arrives.
 */
class PortionPickerState(
    val food: FoodOut,
    portions: List<PortionOut> = emptyList(),
    private val unitSystem: UnitSystem = UnitSystem.IMPERIAL,
    initialQuantity: Double? = null,
    initialUnit: String? = null,
    initialPortionGramWeight: Double? = null,
) {
    /** The food defines its own "1 serving" (per-serving kcal or a serving size in grams). */
    val hasServing: Boolean = food.kcalPerServing != null || food.servingSize != null

    var portions: List<PortionOut> by mutableStateOf(portions)
        private set
    var unit: PickerUnit by mutableStateOf(PickerUnit.Gram)
        private set
    var quantityText: String by mutableStateOf("")
        private set

    // A recent re-log's named portion may not be in [portions] yet (detail still loading) —
    // remember what to restore when it arrives. Cleared once restored or the user picks a unit.
    private var pendingGramWeight: Double? = null
    private var pendingLabel: String? = null

    init {
        unit = resolveInitialUnit(initialUnit, initialPortionGramWeight)
        quantityText = initialQuantity?.let(::formatQuantity) ?: defaultQuantityFor(unit)
    }

    val quantity: Double?
        get() = quantityText.toDoubleOrNull()?.takeIf { it > 0 }

    /** The current amount in grams — the conversion pivot. Null when the quantity is invalid or
     *  the unit is a serving with no gram basis (per-serving-only foods). */
    val gramsEquivalent: Double?
        get() {
            val q = quantity ?: return null
            return when (val u = unit) {
                PickerUnit.Gram -> q
                PickerUnit.Oz -> Units.ozToG(q)
                is PickerUnit.Named -> q * u.portion.gramWeight
                PickerUnit.Serving -> food.servingSize?.let { q * it }
            }
        }

    /** True when the quantity field should get the stepper + fraction presets (count-like). */
    val isCountUnit: Boolean
        get() = unit is PickerUnit.Named || unit == PickerUnit.Serving

    /** Client-side preview only; the server computes the authoritative snapshot. */
    fun estimateKcal(): Double? {
        val q = quantity ?: return null
        return when (unit) {
            PickerUnit.Serving -> when {
                food.kcalPerServing != null -> food.kcalPerServing * q
                food.servingSize != null -> food.kcalPer100g * (q * food.servingSize / 100.0)
                else -> food.kcalPer100g * q
            }
            else -> gramsEquivalent?.let { food.kcalPer100g * it / 100.0 }
        }
    }

    fun onQuantityChange(text: String) {
        quantityText = text
    }

    fun setQuantity(value: Double) {
        quantityText = formatQuantity(value)
    }

    /** Stepper: ±1 whole unit, clamped to the smallest loggable fraction. */
    fun step(delta: Double) {
        val next = ((quantity ?: 0.0) + delta).coerceAtLeast(MIN_QUANTITY)
        setQuantity(next)
    }

    /** Switch units by **converting** the current amount through grams — never a reset. */
    fun switchUnit(new: PickerUnit) {
        if (new == unit) return
        pendingGramWeight = null
        pendingLabel = null
        val grams = gramsEquivalent
        quantityText = when (new) {
            PickerUnit.Gram -> grams?.let(::formatQuantity) ?: defaultQuantityFor(new)
            PickerUnit.Oz -> grams?.let { formatQuantity(Units.gToOz(it)) } ?: defaultQuantityFor(new)
            is PickerUnit.Named ->
                grams?.let { formatQuantity(it / new.portion.gramWeight) } ?: defaultQuantityFor(new)
            PickerUnit.Serving -> food.servingSize
                ?.let { size -> grams?.let { formatQuantity(it / size) } }
                ?: defaultQuantityFor(new)
        }
        unit = new
    }

    /** Named portions arrived (the detail request). Restores a pending recent-portion match but
     *  never disturbs a unit the user has already picked. */
    fun updatePortions(list: List<PortionOut>) {
        portions = list
        val match = list.firstOrNull { p ->
            pendingGramWeight?.let { abs(p.gramWeight - it) < 0.01 } == true ||
                pendingLabel?.let { p.description == it || p.description.take(32) == it } == true
        }
        if (match != null) {
            unit = PickerUnit.Named(match)
            pendingGramWeight = null
            pendingLabel = null
        }
    }

    /** The log request for the current state, or null while the quantity is invalid. */
    fun toLogArgs(): LogArgs? {
        val q = quantity ?: return null
        return when (val u = unit) {
            PickerUnit.Serving -> LogArgs(q, "serving")
            PickerUnit.Gram -> LogArgs(q, "g")
            PickerUnit.Oz -> LogArgs(q, "oz")
            is PickerUnit.Named -> LogArgs(q, u.portion.description, u.portion.id)
        }
    }

    /** Label for the quantity field: the unit's own name. */
    fun quantityLabel(): String = when (val u = unit) {
        PickerUnit.Serving -> "Servings"
        PickerUnit.Gram -> "Grams"
        PickerUnit.Oz -> "Ounces"
        is PickerUnit.Named -> u.portion.description
    }

    private fun resolveInitialUnit(initialUnit: String?, initialGramWeight: Double?): PickerUnit {
        // A recent re-log by named portion: match by the snapshotted gram weight, else by label.
        if (initialGramWeight != null) {
            portions.firstOrNull { abs(it.gramWeight - initialGramWeight) < 0.01 }
                ?.let { return PickerUnit.Named(it) }
            pendingGramWeight = initialGramWeight
            pendingLabel = initialUnit
            return fallbackUnit()
        }
        return when (initialUnit?.lowercase()) {
            null -> fallbackUnit()
            "serving", "servings" -> if (hasServing) PickerUnit.Serving else fallbackUnit()
            "g", "gram", "grams" -> PickerUnit.Gram
            "oz", "ounce", "ounces" -> PickerUnit.Oz
            else -> {
                // A portion label (possibly truncated to 32 by the log table).
                portions.firstOrNull {
                    it.description == initialUnit || it.description.take(32) == initialUnit
                }?.let { return PickerUnit.Named(it) }
                pendingLabel = initialUnit
                fallbackUnit()
            }
        }
    }

    /** Default unit when nothing is pre-filled: the food's own serving, else its first named
     *  portion, else the user's preferred mass unit (imperial → oz, metric → g). */
    private fun fallbackUnit(): PickerUnit = when {
        hasServing -> PickerUnit.Serving
        portions.isNotEmpty() -> PickerUnit.Named(portions.first())
        unitSystem == UnitSystem.IMPERIAL -> PickerUnit.Oz
        else -> PickerUnit.Gram
    }

    private fun defaultQuantityFor(unit: PickerUnit): String = when (unit) {
        PickerUnit.Gram -> "100"
        PickerUnit.Oz -> "4"
        else -> "1"
    }
}

/** Two decimals max, trailing zeros trimmed: 8.4657 → "8.47", 240.0 → "240", 0.5 → "0.5". */
internal fun formatQuantity(value: Double): String {
    val rounded = (value * 100).roundToInt() / 100.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

/**
 * The quantity + unit section of the add dialog: named-portion / serving / grams / ounces chips,
 * a converting quantity field (stepper + fraction presets for count-like units), the food's
 * serving label, and a live kcal estimate.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortionPicker(state: PortionPickerState, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (state.isCountUnit) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { state.step(-1.0) }) {
                    Icon(Icons.Outlined.Remove, contentDescription = "Less")
                }
                OutlinedTextField(
                    value = state.quantityText,
                    onValueChange = state::onQuantityChange,
                    label = { Text(state.quantityLabel()) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { state.step(1.0) }) {
                    Icon(Icons.Outlined.Add, contentDescription = "More")
                }
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUANTITY_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = state.quantity == preset,
                        onClick = { state.setQuantity(preset) },
                        label = { Text(formatFraction(preset)) },
                    )
                }
            }
        } else {
            OutlinedTextField(
                value = state.quantityText,
                onValueChange = state::onQuantityChange,
                label = { Text(state.quantityLabel()) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.portions.forEach { portion ->
                FilterChip(
                    selected = (state.unit as? PickerUnit.Named)?.portion?.id == portion.id,
                    onClick = { state.switchUnit(PickerUnit.Named(portion)) },
                    label = { Text(portion.description) },
                )
            }
            if (state.hasServing) {
                FilterChip(
                    selected = state.unit == PickerUnit.Serving,
                    onClick = { state.switchUnit(PickerUnit.Serving) },
                    label = { Text("Serving") },
                )
            }
            FilterChip(
                selected = state.unit == PickerUnit.Gram,
                onClick = { state.switchUnit(PickerUnit.Gram) },
                label = { Text("Grams") },
            )
            FilterChip(
                selected = state.unit == PickerUnit.Oz,
                onClick = { state.switchUnit(PickerUnit.Oz) },
                label = { Text("Ounces") },
            )
        }
        state.food.servingLabel?.let { label ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Serving: $label",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.estimateKcal()?.let { kcal ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "≈ ${kcal.roundToInt()} kcal",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                state.gramsEquivalent?.let { grams ->
                    if (state.isCountUnit) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "· ${formatQuantity(grams)} g",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Preset chip labels: 0.25 → "¼", 0.5 → "½", 1.5 → "1½", whole numbers plain. */
internal fun formatFraction(value: Double): String {
    val whole = value.toInt()
    val frac = value - whole
    val fracText = when {
        abs(frac - 0.25) < 0.001 -> "¼"
        abs(frac - 0.5) < 0.001 -> "½"
        abs(frac - 0.75) < 0.001 -> "¾"
        else -> ""
    }
    return when {
        fracText.isEmpty() -> formatQuantity(value)
        whole == 0 -> fracText
        else -> "$whole$fracText"
    }
}
