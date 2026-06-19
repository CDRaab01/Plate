package com.plate.ui.calendar

import com.plate.data.remote.DaySummary
import com.plate.data.remote.RangeSummary
import com.plate.data.remote.TotalsOut
import com.plate.data.repository.SummaryRepository
import com.plate.util.MainDispatcherRule
import com.plate.util.PendingDiaryDate
import com.plate.util.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

private val ZERO_TOTALS = TotalsOut(0.0, 0.0, 0.0, 0.0)
private val SAMPLE_TOTALS = TotalsOut(1800.0, 120.0, 200.0, 60.0)

private fun sampleSummary(year: Int = 2026, month: Int = 6): RangeSummary {
    val days = (1..30).map { d ->
        DaySummary(
            date = "%04d-%02d-%02d".format(year, month, d),
            totals = if (d % 3 == 0) ZERO_TOTALS else SAMPLE_TOTALS,
            targetKcal = 2000.0,
            trained = d % 5 == 0,
        )
    }
    return RangeSummary(
        start = "%04d-%02d-01".format(year, month),
        end = "%04d-%02d-30".format(year, month),
        days = days,
        total = SAMPLE_TOTALS,
        averages = SAMPLE_TOTALS,
    )
}

private class FakeSummaryRepository(
    private val summary: RangeSummary = sampleSummary(),
    private val failWith: Exception? = null,
    var lastStart: String? = null,
    var lastEnd: String? = null,
) : SummaryRepository {
    override suspend fun getSummary(start: String?, end: String?): RangeSummary {
        lastStart = start
        lastEnd = end
        failWith?.let { throw it }
        return summary
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `init loads current month into Success`() = runTest {
        val vm = CalendarViewModel(FakeSummaryRepository())
        advanceUntilIdle()
        val state = vm.monthData.value
        assertTrue(state is UiState.Success)
        assertEquals(30, (state as UiState.Success).data.size)
    }

    @Test
    fun `init passes month bounds to repository`() = runTest {
        val repo = FakeSummaryRepository()
        CalendarViewModel(repo)
        advanceUntilIdle()
        val now = YearMonth.now()
        assertEquals(now.atDay(1).toString(), repo.lastStart)
        assertEquals(now.atEndOfMonth().toString(), repo.lastEnd)
    }

    @Test
    fun `prevMonth loads the previous month`() = runTest {
        val repo = FakeSummaryRepository()
        val vm = CalendarViewModel(repo)
        advanceUntilIdle()

        val prev = YearMonth.now().minusMonths(1)
        vm.prevMonth()
        advanceUntilIdle()

        assertEquals(prev, vm.displayedMonth.value)
        assertEquals(prev.atDay(1).toString(), repo.lastStart)
    }

    @Test
    fun `nextMonth is blocked at current month`() = runTest {
        val repo = FakeSummaryRepository()
        val vm = CalendarViewModel(repo)
        advanceUntilIdle()

        val before = vm.displayedMonth.value
        vm.nextMonth()
        advanceUntilIdle()

        assertEquals(before, vm.displayedMonth.value)
    }

    @Test
    fun `nextMonth advances when on a past month`() = runTest {
        val repo = FakeSummaryRepository()
        val vm = CalendarViewModel(repo)
        advanceUntilIdle()

        vm.prevMonth()
        advanceUntilIdle()
        val past = vm.displayedMonth.value

        vm.nextMonth()
        advanceUntilIdle()

        assertEquals(past.plusMonths(1), vm.displayedMonth.value)
    }

    @Test
    fun `selectDate toggles detail and deselects on second tap`() = runTest {
        val vm = CalendarViewModel(FakeSummaryRepository())
        advanceUntilIdle()

        val date = vm.displayedMonth.value.atDay(10)
        vm.selectDate(date)
        assertEquals(date, vm.selectedDate.value)

        vm.selectDate(date)
        assertNull(vm.selectedDate.value)
    }

    @Test
    fun `loadMonth clears selected date`() = runTest {
        val vm = CalendarViewModel(FakeSummaryRepository())
        advanceUntilIdle()

        vm.selectDate(vm.displayedMonth.value.atDay(5))
        vm.prevMonth()
        advanceUntilIdle()

        assertNull(vm.selectedDate.value)
    }

    @Test
    fun `network failure emits Error`() = runTest {
        val vm = CalendarViewModel(FakeSummaryRepository(failWith = RuntimeException("offline")))
        advanceUntilIdle()
        assertTrue(vm.monthData.value is UiState.Error)
    }

    @Test
    fun `refresh reloads the same month`() = runTest {
        val repo = FakeSummaryRepository()
        val vm = CalendarViewModel(repo)
        advanceUntilIdle()

        repo.lastStart = null
        vm.refresh()
        advanceUntilIdle()

        assertEquals(vm.displayedMonth.value.atDay(1).toString(), repo.lastStart)
    }

    @Test
    fun `requestDay parks the date for the diary`() = runTest {
        val pending = PendingDiaryDate()
        val vm = CalendarViewModel(FakeSummaryRepository(), pending)
        advanceUntilIdle()

        vm.requestDay(LocalDate.parse("2026-06-10"))

        assertEquals("2026-06-10", pending.date.value)
    }
}
