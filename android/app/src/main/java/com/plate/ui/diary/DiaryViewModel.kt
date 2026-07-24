package com.plate.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.ApiService
import com.plate.data.remote.DailyLog
import com.plate.data.repository.BatchLogItem
import com.plate.data.repository.LogRepository
import com.plate.util.PendingDiaryDate
import com.plate.util.UiState
import com.plate.util.userMessage
import com.plate.widget.WidgetSnapshotWriter
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
    private val pendingDate: PendingDiaryDate = PendingDiaryDate(),
    // Nullable/default so plain-JVM unit tests can construct the VM without a Context-backed writer;
    // Hilt always injects the real one in production (same pattern as pendingDate).
    private val widgetSnapshotWriter: WidgetSnapshotWriter? = null,
) : ViewModel() {

    private val _date = MutableStateFlow(LocalDate.now().toString())
    val date: StateFlow<String> = _date

    private val _day = MutableStateFlow<UiState<DailyLog>>(UiState.Loading)
    val day: StateFlow<UiState<DailyLog>> = _day

    /**
     * Non-null when the currently shown day was served from the offline cache: its capture time,
     * driving the stale banner. Null whenever the day is fresh (or errored).
     */
    private val _staleAsOfMs = MutableStateFlow<Long?>(null)
    val staleAsOfMs: StateFlow<Long?> = _staleAsOfMs

    private val now get() = LocalTime.now()
    private val _greeting = MutableStateFlow(greetingForTime(LocalTime.now()))
    val greeting: StateFlow<String> = _greeting

    private val _mealNudge = MutableStateFlow(mealNudgeForTime(LocalTime.now()))
    val mealNudge: StateFlow<String> = _mealNudge

    init {
        load()
        loadGreeting()
        observePendingDate()
    }

    /**
     * Honour a day requested from the Calendar tab (via [PendingDiaryDate]). The flow replays its
     * current value to this collector, so a date parked before this VM existed is picked up on
     * start; later requests jump days while the VM is alive.
     */
    private fun observePendingDate() {
        viewModelScope.launch {
            pendingDate.date.collect { requested ->
                requested ?: return@collect
                setDate(requested)
                pendingDate.consume()
            }
        }
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

    /** Switch the diary (and the shared add/search/scan/photo flows) to another day, then reload. */
    fun setDate(date: String) {
        if (date == _date.value) return
        _date.value = date
        load()
    }

    /** Step to the previous day. */
    fun prevDay() = setDate(LocalDate.parse(_date.value).minusDays(1).toString())

    /** Step to the next day — capped at today (no logging into the future). */
    fun nextDay() {
        val next = LocalDate.parse(_date.value).plusDays(1)
        if (!next.isAfter(LocalDate.now())) setDate(next.toString())
    }

    /** Jump back to today. */
    fun goToToday() = setDate(LocalDate.now().toString())

    fun load() {
        viewModelScope.launch {
            _day.value = UiState.Loading
            _day.value = try {
                val stale = logRepository.getDayStale(_date.value)
                val day = stale.value
                _staleAsOfMs.value = stale.asOfMs
                // Keep the home-screen widget current when the diary being edited is today's — every
                // add/edit/delete/quick-add reloads through here.
                if (_date.value == LocalDate.now().toString()) {
                    runCatching { widgetSnapshotWriter?.write(day) }
                }
                UiState.Success(day)
            } catch (e: Exception) {
                _staleAsOfMs.value = null
                UiState.Error(e.userMessage("Couldn't load your day"))
            }
        }
    }

    fun addEntry(
        foodId: String,
        meal: String,
        quantity: Double,
        unit: String,
        portionId: String? = null,
    ) {
        viewModelScope.launch {
            try {
                logRepository.addEntry(foodId, _date.value, meal, quantity, unit, portionId)
                load()
            } catch (e: Exception) {
                _day.value = UiState.Error(e.message ?: "Couldn't log that food")
            }
        }
    }

    /**
     * Multi-select add: log several foods into one meal at once (each at its default portion —
     * picked on the search screen; the user edits any of them from the diary afterward). Reloads
     * the day on success. [onDone] fires only on success so the caller can pop back / clear.
     */
    fun addEntries(items: List<BatchLogItem>, meal: String, onDone: () -> Unit = {}) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            try {
                logRepository.addEntries(_date.value, meal, items)
                load()
                onDone()
            } catch (e: Exception) {
                _day.value = UiState.Error(e.message ?: "Couldn't log those foods")
            }
        }
    }

    /** Edit an existing entry's portion and/or meal, then reload the day. */
    fun updateEntry(id: String, quantity: Double, meal: String) {
        viewModelScope.launch {
            try {
                logRepository.updateEntry(id = id, quantity = quantity, meal = meal)
                load()
            } catch (e: Exception) {
                _day.value = UiState.Error(e.message ?: "Couldn't update that entry")
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

    /** Copy the previous day's entries into the current day (the "copy yesterday" quick-log). */
    fun copyYesterday() {
        viewModelScope.launch {
            val target = _date.value
            val from = LocalDate.parse(target).minusDays(1).toString()
            try {
                logRepository.copyDay(from, target)
                load()
            } catch (e: Exception) {
                _day.value = UiState.Error(e.message ?: "Couldn't copy yesterday")
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
