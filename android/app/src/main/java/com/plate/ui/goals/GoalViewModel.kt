package com.plate.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.GoalOut
import com.plate.data.remote.GoalUpsertRequest
import com.plate.data.repository.GoalRepository
import com.plate.util.AppPreferences
import com.plate.util.UiState
import com.plate.util.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Loads the user's active goal on entry (404 = no goal yet) and handles saving a new one.
 * On save success the state is refreshed so the DiaryScreen picks up the new targets.
 */
@HiltViewModel
class GoalViewModel @Inject constructor(
    private val goalRepository: GoalRepository,
    appPreferences: AppPreferences,
) : ViewModel() {

    /** Drives whether the form inputs are labelled/entered in lb·in or kg·cm. */
    val unitSystem: StateFlow<UnitSystem> = appPreferences.unitSystem
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UnitSystem.IMPERIAL)

    private val _goal = MutableStateFlow<UiState<GoalOut?>>(UiState.Loading)
    val goal: StateFlow<UiState<GoalOut?>> = _goal

    private val _saveState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val saveState: StateFlow<UiState<Unit>> = _saveState

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _goal.value = UiState.Loading
            _goal.value = try {
                UiState.Success(goalRepository.getGoal())
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 404) UiState.Success(null) else UiState.Error(e.message ?: "Error loading goal")
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Error loading goal")
            }
        }
    }

    fun save(request: GoalUpsertRequest) {
        viewModelScope.launch {
            _saveState.value = UiState.Loading
            _saveState.value = try {
                goalRepository.setGoal(request)
                load()
                UiState.Success(Unit)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Failed to save goal")
            }
        }
    }

    fun clearSaveState() {
        _saveState.value = UiState.Idle
    }
}
