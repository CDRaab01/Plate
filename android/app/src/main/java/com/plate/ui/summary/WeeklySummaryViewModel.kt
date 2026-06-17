package com.plate.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.RangeSummary
import com.plate.data.repository.SummaryRepository
import com.plate.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Weekly summary (Phase 8): the last 7 days of macro totals + the period's averages. */
@HiltViewModel
class WeeklySummaryViewModel @Inject constructor(
    private val summaryRepository: SummaryRepository,
) : ViewModel() {

    private val _summary = MutableStateFlow<UiState<RangeSummary>>(UiState.Loading)
    val summary: StateFlow<UiState<RangeSummary>> = _summary

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _summary.value = UiState.Loading
            _summary.value = try {
                UiState.Success(summaryRepository.getSummary())
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load your week")
            }
        }
    }
}
