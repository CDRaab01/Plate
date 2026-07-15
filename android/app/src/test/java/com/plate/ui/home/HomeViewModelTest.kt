package com.plate.ui.home

import com.plate.data.local.db.BodyMetricEntity
import com.plate.data.remote.ApiService
import com.plate.data.remote.BodyMetricOut
import com.plate.data.remote.DailyLog
import com.plate.data.remote.MealGroup
import com.plate.data.remote.TotalsOut
import com.plate.data.remote.WeightTrendOut
import com.plate.data.repository.LogRepository
import com.plate.data.repository.MetricRepository
import com.plate.util.AppPreferences
import com.plate.util.MainDispatcherRule
import com.plate.util.UiState
import com.plate.util.UnitSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private fun totals(kcal: Double, p: Double, c: Double, f: Double) = TotalsOut(kcal, p, c, f)

private fun dailyLog(trained: Boolean = false) = DailyLog(
    date = "2026-06-30",
    meals = listOf(MealGroup("breakfast", emptyList(), totals(0.0, 0.0, 0.0, 0.0))),
    totals = totals(800.0, 60.0, 90.0, 20.0),
    targets = totals(2000.0, 150.0, 200.0, 67.0),
    trainedToday = trained,
)

private class FakeMetricRepository(
    override val metrics: Flow<List<BodyMetricEntity>> = flowOf(emptyList()),
    private val trend: WeightTrendOut? = null,
) : MetricRepository {
    var synced = 0
    var lastAdd: Triple<String, Double, UnitSystem>? = null

    override suspend fun sync() { synced++ }

    override suspend fun addMetric(date: String, weight: Double, unit: UnitSystem, bodyfat: Double?): BodyMetricOut {
        lastAdd = Triple(date, weight, unit)
        return BodyMetricOut("m", "u", date, weight * 0.45359237, weight, unit.weightUnit, bodyfat)
    }

    override suspend fun getTrend(): WeightTrendOut =
        trend ?: WeightTrendOut(emptyList(), null, null, 0.0, "lb", "insufficient_data")
}

// Minimal LogRepository fake returning a fixed day.
private class FakeLog(private val day: DailyLog) : LogRepository {
    override suspend fun getDay(date: String) = day
    override suspend fun addEntry(foodId: String, date: String, meal: String, quantity: Double, unit: String) = error("unused")
    override suspend fun updateEntry(id: String, quantity: Double?, unit: String?, meal: String?) = error("unused")
    override suspend fun deleteEntry(id: String) {}
    override suspend fun quickAdd(date: String, meal: String, name: String?, kcal: Double, proteinG: Double, carbsG: Double, fatG: Double) = error("unused")
    override suspend fun copyDay(fromDate: String, toDate: String) = emptyList<com.plate.data.remote.LogEntryOut>()

    override suspend fun logRecipe(recipeId: String, date: String, meal: String) = emptyList<com.plate.data.remote.LogEntryOut>()
    override suspend fun syncPending() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private fun prefs(unit: UnitSystem = UnitSystem.IMPERIAL): AppPreferences =
        mock { whenever(it.unitSystem).thenReturn(flowOf(unit)) }

    private fun vm(
        day: DailyLog = dailyLog(),
        metric: FakeMetricRepository = FakeMetricRepository(),
        unit: UnitSystem = UnitSystem.IMPERIAL,
    ) = HomeViewModel(FakeLog(day), metric, prefs(unit), mock<ApiService>())

    @Test
    fun `init loads today into Success`() = runTest {
        val home = vm()
        advanceUntilIdle()
        val s = home.state.value
        assertTrue(s is UiState.Success)
        assertEquals(2000.0, (s as UiState.Success).data.day.targets.kcal, 0.0)
        assertEquals(800.0, s.data.day.totals.kcal, 0.0)
    }

    @Test
    fun `trained-today flag surfaces`() = runTest {
        val home = vm(day = dailyLog(trained = true))
        advanceUntilIdle()
        val s = home.state.value as UiState.Success
        assertTrue(s.data.day.trainedToday)
    }

    @Test
    fun `trend passthrough`() = runTest {
        val trend = WeightTrendOut(emptyList(), 180.0, -0.5, -0.5, "lb", "on_pace")
        val home = vm(metric = FakeMetricRepository(trend = trend))
        advanceUntilIdle()
        val s = home.state.value as UiState.Success
        assertEquals("on_pace", s.data.trend?.status)
    }

    @Test
    fun `sync runs on load`() = runTest {
        val metric = FakeMetricRepository()
        val home = vm(metric = metric)
        advanceUntilIdle()
        assertTrue(metric.synced >= 1)
        // keep a reference so the unused-var lint stays quiet
        assertTrue(home.state.value is UiState.Success)
    }

    @Test
    fun `logBodyweight sends the current unit and reloads`() = runTest {
        val metric = FakeMetricRepository()
        val home = vm(metric = metric, unit = UnitSystem.IMPERIAL)
        advanceUntilIdle()

        home.logBodyweight(198.0)
        advanceUntilIdle()

        assertEquals(UnitSystem.IMPERIAL, metric.lastAdd?.third)
        assertEquals(198.0, metric.lastAdd?.second)
    }
}
