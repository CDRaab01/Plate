package com.plate.ui.summary

import com.plate.data.remote.DaySummary
import com.plate.data.remote.RangeSummary
import com.plate.data.remote.TotalsOut
import com.plate.data.repository.SummaryRepository
import com.plate.util.MainDispatcherRule
import com.plate.util.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private fun sampleSummary() = RangeSummary(
    start = "2026-06-10",
    end = "2026-06-16",
    days = (10..16).map { d ->
        DaySummary("2026-06-$d", TotalsOut(1800.0, 120.0, 180.0, 60.0), 2000.0, trained = d % 2 == 0)
    },
    total = TotalsOut(12600.0, 840.0, 1260.0, 420.0),
    averages = TotalsOut(1800.0, 120.0, 180.0, 60.0),
)

private class FakeSummaryRepository(
    private val summary: RangeSummary = sampleSummary(),
    private val failWith: Exception? = null,
) : SummaryRepository {
    override suspend fun getSummary(start: String?, end: String?): RangeSummary {
        failWith?.let { throw it }
        return summary
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WeeklySummaryViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `init loads the week into Success`() = runTest {
        val vm = WeeklySummaryViewModel(FakeSummaryRepository())
        advanceUntilIdle()
        val state = vm.summary.value
        assertTrue(state is UiState.Success)
        assertEquals(7, (state as UiState.Success).data.days.size)
        assertEquals(1800.0, state.data.averages.kcal, 0.001)
    }

    @Test
    fun `load failure emits Error`() = runTest {
        val vm = WeeklySummaryViewModel(FakeSummaryRepository(failWith = RuntimeException("offline")))
        advanceUntilIdle()
        assertTrue(vm.summary.value is UiState.Error)
    }
}
