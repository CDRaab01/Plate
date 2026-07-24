package com.plate.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.FoodDetailOut
import com.plate.data.repository.FoodRepository
import com.plate.util.AppPreferences
import com.plate.util.UiState
import com.plate.util.UnitSystem
import com.plate.util.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Drives the barcode scan → lookup flow (CLAUDE.md §6). The camera analyzer fires continuously, so
 * we ignore further reads once a lookup is in flight or has resolved a product — only the first
 * clean read acts. A missing product (HTTP 404) surfaces as a friendly error, not a crash.
 */
@HiltViewModel
class BarcodeScanViewModel @Inject constructor(
    private val foodRepository: FoodRepository,
    appPreferences: AppPreferences,
) : ViewModel() {

    // Detail shape: the barcode response carries the product's named portions for the dialog.
    private val _state = MutableStateFlow<UiState<FoodDetailOut>>(UiState.Idle)
    val state: StateFlow<UiState<FoodDetailOut>> = _state

    /** Drives the add dialog's imperial/metric-aware default unit. */
    val unitSystem: StateFlow<UnitSystem> = appPreferences.unitSystem
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UnitSystem.IMPERIAL)

    private var lookupJob: Job? = null

    fun onBarcodeScanned(code: String) {
        // Once we're looking something up or have a hit, stop reacting to the camera's stream.
        if (_state.value is UiState.Loading || _state.value is UiState.Success) return
        if (code.isBlank()) return
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = try {
                UiState.Success(foodRepository.lookupBarcode(code.trim()))
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    UiState.Error("No product found for that barcode")
                } else {
                    UiState.Error(e.message ?: "Lookup failed")
                }
            } catch (e: Exception) {
                UiState.Error(e.userMessage("Lookup failed"))
            }
        }
    }

    /** Return to scanning after a not-found / failed lookup so the user can try another product. */
    fun reset() {
        lookupJob?.cancel()
        _state.value = UiState.Idle
    }
}
