package com.plate.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.DaySummary
import com.plate.data.repository.SummaryRepository
import com.plate.util.PendingDiaryDate
import com.plate.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val summaryRepository: SummaryRepository,
    private val pendingDate: PendingDiaryDate = PendingDiaryDate(),
) : ViewModel() {

    private val _displayedMonth = MutableStateFlow(YearMonth.now())
    val displayedMonth: StateFlow<YearMonth> = _displayedMonth.asStateFlow()

    private val _monthData = MutableStateFlow<UiState<List<DaySummary>>>(UiState.Idle)
    val monthData: StateFlow<UiState<List<DaySummary>>> = _monthData

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    init {
        loadMonth(YearMonth.now())
    }

    fun refresh() = loadMonth(_displayedMonth.value)

    fun loadMonth(month: YearMonth) {
        _displayedMonth.value = month
        _selectedDate.value = null
        viewModelScope.launch {
            _monthData.value = UiState.Loading
            _monthData.value = try {
                val summary = summaryRepository.getSummary(
                    start = month.atDay(1).toString(),
                    end = month.atEndOfMonth().toString(),
                )
                UiState.Success(summary.days)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load calendar")
            }
        }
    }

    /** Advance one month — blocked at the current month (no future food data). */
    fun nextMonth() {
        val next = _displayedMonth.value.plusMonths(1)
        if (!next.isAfter(YearMonth.now())) loadMonth(next)
    }

    fun prevMonth() = loadMonth(_displayedMonth.value.minusMonths(1))

    fun selectDate(date: LocalDate) {
        _selectedDate.value = if (_selectedDate.value == date) null else date
    }

    /** Park [date] for the Diary tab so opening it lands on that day (the caller switches tabs). */
    fun requestDay(date: LocalDate) = pendingDate.request(date.toString())
}
