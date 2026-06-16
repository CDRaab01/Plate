package com.plate.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.FoodOut
import com.plate.data.repository.FoodRepository
import com.plate.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debounced food search. The query is debounced so we never issue a request per keystroke — the
 * server only reaches the external USDA/OFF APIs on a cache miss, so keeping calls coarse matters
 * (CLAUDE.md §5).
 */
@HiltViewModel
class FoodSearchViewModel @Inject constructor(
    private val foodRepository: FoodRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<UiState<List<FoodOut>>>(UiState.Idle)
    val results: StateFlow<UiState<List<FoodOut>>> = _results

    private var searchJob: Job? = null

    fun onQueryChange(value: String) {
        _query.value = value
        searchJob?.cancel()
        if (value.isBlank()) {
            _results.value = UiState.Idle
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _results.value = UiState.Loading
            _results.value = try {
                UiState.Success(foodRepository.search(value.trim()))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Search failed")
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 350L
    }
}
