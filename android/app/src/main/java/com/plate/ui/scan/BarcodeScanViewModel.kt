package com.plate.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.FoodOut
import com.plate.data.repository.FoodRepository
import com.plate.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<FoodOut>>(UiState.Idle)
    val state: StateFlow<UiState<FoodOut>> = _state

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
                UiState.Error(e.message ?: "Lookup failed")
            }
        }
    }

    /** Return to scanning after a not-found / failed lookup so the user can try another product. */
    fun reset() {
        lookupJob?.cancel()
        _state.value = UiState.Idle
    }
}
