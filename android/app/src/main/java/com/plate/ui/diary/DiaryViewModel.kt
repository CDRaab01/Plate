package com.plate.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.ApiService
import com.plate.data.remote.DailyLog
import com.plate.data.repository.LogRepository
import com.plate.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/**
 * The day's diary: loads the meal-split log + totals vs targets for a date, and drives
 * add/edit/delete. This is the shared source of truth for both the diary and search screens, so
 * logging a food from search reloads here automatically.
 */
@HiltViewModel
class DiaryViewModel @Inject constructor(
    private val logRepository: LogRepository,
    private val api: ApiService,
) : ViewModel() {

    private val _date = MutableStateFlow(LocalDate.now().toString())
    val date: StateFlow<String> = _date

    private val _day = MutableStateFlow<UiState<DailyLog>>(UiState.Loading)
    val day: StateFlow<UiState<DailyLog>> = _day

    private val now get() = LocalTime.now()
    private val _greeting = MutableStateFlow(greetingForTime(LocalTime.now()))
    val greeting: StateFlow<String> = _greeting

    private val _mealNudge = MutableStateFlow(mealNudgeForTime(LocalTime.now()))
    val mealNudge: StateFlow<String> = _mealNudge

    init {
        load()
        loadGreeting()
    }

    private fun loadGreeting() {
        viewModelScope.launch {
            val firstName = try {
                api.me().name.trim().substringBefore(' ').trim()
            } catch (_: Exception) {
                null
            }
            _greeting.value = greetingForTime(now, firstName)
        }
    }

    fun load() {
        viewModelScope.launch {
            _day.value = UiState.Loading
            _day.value = try {
                UiState.Success(logRepository.getDay(_date.value))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load your day")
            }
        }
    }

    fun addEntry(foodId: String, meal: String, quantity: Double, unit: String) {
        viewModelScope.launch {
            try {
                logRepository.addEntry(foodId, _date.value, meal, quantity, unit)
                load()
            } catch (e: Exception) {
                _day.value = UiState.Error(e.message ?: "Couldn't log that food")
            }
        }
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch {
            try {
                logRepository.deleteEntry(id)
                load()
            } catch (e: Exception) {
                _day.value = UiState.Error(e.message ?: "Couldn't delete that entry")
            }
        }
    }

    /** Quick add (Phase 8): log raw macros directly, with no source food, then reload the day. */
    fun quickAdd(
        meal: String,
        name: String?,
        kcal: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
    ) {
        viewModelScope.launch {
            try {
                logRepository.quickAdd(_date.value, meal, name, kcal, proteinG, carbsG, fatG)
                load()
            } catch (e: Exception) {
                _day.value = UiState.Error(e.message ?: "Couldn't quick-add that")
            }
        }
    }

    private companion object {
        fun greetingForTime(time: LocalTime, firstName: String? = null): String {
            val base = when (time.hour) {
                in 5..11 -> "Good morning"
                in 12..16 -> "Good afternoon"
                else -> "Good evening"
            }
            return if (firstName.isNullOrBlank()) base else "$base, $firstName"
        }

        fun mealNudgeForTime(time: LocalTime): String = when (time.hour) {
            in 5..10 -> "What's for breakfast today?"
            in 11..13 -> "Lunch time — log your midday meal"
            in 14..16 -> "What's for an afternoon snack?"
            in 17..21 -> "Dinner time — what are you having?"
            else -> "Track your meals for today"
        }
    }
}
