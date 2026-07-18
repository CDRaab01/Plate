package com.plate.ui.restaurant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.RestaurantComponentOut
import com.plate.data.remote.RestaurantLogSelection
import com.plate.data.remote.RestaurantOut
import com.plate.data.repository.RestaurantRepository
import com.plate.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Client-side running totals for the ticked components (display only — the server recomputes). */
data class RunningTotals(
    val kcal: Double = 0.0,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
)

/**
 * Scale a component's default-portion macros to an overridden quantity. The Out carries macros at
 * the component's default portion, so the preview scales linearly; unlinked components (null kcal)
 * contribute nothing.
 */
fun componentTotals(component: RestaurantComponentOut, quantity: Double?): RunningTotals {
    val kcal = component.kcal ?: return RunningTotals()
    val factor =
        if (quantity == null || component.quantity <= 0.0) 1.0 else quantity / component.quantity
    return RunningTotals(
        kcal = kcal * factor,
        proteinG = (component.proteinG ?: 0.0) * factor,
        carbsG = (component.carbsG ?: 0.0) * factor,
        fatG = (component.fatG ?: 0.0) * factor,
    )
}

/** Sum the ticked components' previews. Pure — unit-tested. */
fun runningTotals(
    components: List<RestaurantComponentOut>,
    checked: Set<String>,
    overrides: Map<String, Double>,
): RunningTotals {
    var totals = RunningTotals()
    for (component in components) {
        if (component.id !in checked) continue
        val part = componentTotals(component, overrides[component.id])
        totals = RunningTotals(
            kcal = totals.kcal + part.kcal,
            proteinG = totals.proteinG + part.proteinG,
            carbsG = totals.carbsG + part.carbsG,
            fatG = totals.fatG + part.fatG,
        )
    }
    return totals
}

/** Ticked components → selections; an override rides along only when it differs from the default. */
fun buildSelections(
    components: List<RestaurantComponentOut>,
    checked: Set<String>,
    overrides: Map<String, Double>,
): List<RestaurantLogSelection> = components.filter { it.id in checked }.map { component ->
    val override = overrides[component.id]
    RestaurantLogSelection(
        componentId = component.id,
        quantity = override?.takeIf { it > 0.0 && it != component.quantity },
    )
}

/**
 * The checkbox log sheet: load a restaurant, tick components (pre-ticked from default_checked),
 * adjust portions, pick a meal, log — one diary entry per ticked component, server-side.
 */
@HiltViewModel
class RestaurantLogViewModel @Inject constructor(
    private val repository: RestaurantRepository,
) : ViewModel() {

    private val _restaurant = MutableStateFlow<UiState<RestaurantOut>>(UiState.Idle)
    val restaurant: StateFlow<UiState<RestaurantOut>> = _restaurant

    private val _checked = MutableStateFlow<Set<String>>(emptySet())
    val checked: StateFlow<Set<String>> = _checked

    private val _overrides = MutableStateFlow<Map<String, Double>>(emptyMap())
    val overrides: StateFlow<Map<String, Double>> = _overrides

    private val _logState = MutableStateFlow<UiState<Int>>(UiState.Idle)
    val logState: StateFlow<UiState<Int>> = _logState

    fun load(id: String) {
        viewModelScope.launch {
            _restaurant.value = UiState.Loading
            _logState.value = UiState.Idle
            _overrides.value = emptyMap()
            try {
                val restaurant = repository.get(id)
                _restaurant.value = UiState.Success(restaurant)
                _checked.value =
                    restaurant.components.filter { it.defaultChecked }.map { it.id }.toSet()
            } catch (e: Exception) {
                _restaurant.value = UiState.Error(e.message ?: "Couldn't load that restaurant")
            }
        }
    }

    fun toggle(componentId: String) {
        _checked.value =
            if (componentId in _checked.value) _checked.value - componentId
            else _checked.value + componentId
    }

    fun setQuantity(componentId: String, quantity: Double) {
        _overrides.value = _overrides.value + (componentId to quantity)
    }

    fun totals(): RunningTotals {
        val restaurant = (_restaurant.value as? UiState.Success)?.data ?: return RunningTotals()
        return runningTotals(restaurant.components, _checked.value, _overrides.value)
    }

    fun log(meal: String, date: String = LocalDate.now().toString()) {
        val restaurant = (_restaurant.value as? UiState.Success)?.data ?: return
        val selections = buildSelections(restaurant.components, _checked.value, _overrides.value)
        if (selections.isEmpty()) {
            _logState.value = UiState.Error("Tick at least one component")
            return
        }
        viewModelScope.launch {
            _logState.value = UiState.Loading
            _logState.value = try {
                UiState.Success(repository.log(restaurant.id, date, meal, selections))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't log that meal")
            }
        }
    }

    fun clearLogState() {
        _logState.value = UiState.Idle
    }
}
