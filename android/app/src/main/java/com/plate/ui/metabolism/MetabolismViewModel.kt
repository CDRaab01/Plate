package com.plate.ui.metabolism

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.AdaptiveTdeeOut
import com.plate.data.remote.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

/** The metabolism dashboard's state: the adaptive-TDEE read, or why it isn't showing. */
sealed interface MetabolismState {
    data object Loading : MetabolismState
    /** `GET /goals/adaptive` 404s until a goal is set. */
    data object NoGoal : MetabolismState
    data object Error : MetabolismState
    data class Data(val adaptive: AdaptiveTdeeOut) : MetabolismState
}

@HiltViewModel
class MetabolismViewModel @Inject constructor(
    private val api: ApiService,
) : ViewModel() {
    private val _state = MutableStateFlow<MetabolismState>(MetabolismState.Loading)
    val state: StateFlow<MetabolismState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = MetabolismState.Loading
            _state.value = try {
                MetabolismState.Data(api.getAdaptiveTdee())
            } catch (e: HttpException) {
                if (e.code() == 404) MetabolismState.NoGoal else MetabolismState.Error
            } catch (_: Exception) {
                MetabolismState.Error
            }
        }
    }
}
