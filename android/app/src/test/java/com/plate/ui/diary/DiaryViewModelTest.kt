package com.plate.ui.diary

import com.plate.data.remote.ApiService
import com.plate.data.remote.DailyLog
import com.plate.data.remote.LogEntryOut
import com.plate.data.remote.MealGroup
import com.plate.data.remote.TotalsOut
import com.plate.data.repository.LogRepository
import com.plate.util.MainDispatcherRule
import com.plate.util.UiState
import org.mockito.kotlin.mock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private fun emptyTotals() = TotalsOut(0.0, 0.0, 0.0, 0.0)

private fun dailyLog(
    date: String,
    entries: List<LogEntryOut> = emptyList(),
    trainedToday: Boolean = false,
) = DailyLog(
    date = date,
    meals = listOf(
        MealGroup("breakfast", entries, emptyTotals()),
        MealGroup("lunch", emptyList(), emptyTotals()),
        MealGroup("dinner", emptyList(), emptyTotals()),
        MealGroup("snack", emptyList(), emptyTotals()),
    ),
    totals = emptyTotals(),
    targets = TotalsOut(2000.0, 150.0, 200.0, 67.0),
    trainedToday = trainedToday,
)

private class FakeLogRepository(
    var day: DailyLog = dailyLog("2026-06-16"),
    private val failWith: Exception? = null,
) : LogRepository {
    var added = 0
    var deleted = 0
    var updated = 0

    override suspend fun getDay(date: String): DailyLog {
        failWith?.let { throw it }
        return day
    }

    override suspend fun addEntry(
        foodId: String,
        date: String,
        meal: String,
        quantity: Double,
        unit: String,
    ): LogEntryOut {
        added++
        return LogEntryOut("e", foodId, "Food", date, meal, quantity, unit, 100.0, 5.0, 10.0, 2.0)
    }

    override suspend fun updateEntry(id: String, quantity: Double?, unit: String?, meal: String?): LogEntryOut {
        updated++
        return LogEntryOut(id, "f", "Food", "2026-06-16", meal ?: "breakfast", quantity ?: 1.0, unit ?: "g", 100.0, 5.0, 10.0, 2.0)
    }

    override suspend fun deleteEntry(id: String) {
        deleted++
    }

    var quickAdded = 0
    override suspend fun quickAdd(
        date: String,
        meal: String,
        name: String?,
        kcal: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
    ): LogEntryOut {
        quickAdded++
        return LogEntryOut("q", null, name ?: "Quick add", date, meal, 1.0, "serving", kcal, proteinG, carbsG, fatG)
    }

    override suspend fun logRecipe(recipeId: String, date: String, meal: String): List<LogEntryOut> =
        emptyList()

    override suspend fun syncPending() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val fakeApi: ApiService = mock()

    @Test
    fun `init loads the day into Success`() = runTest {
        val repo = FakeLogRepository(day = dailyLog("2026-06-16"))
        val vm = DiaryViewModel(repo, fakeApi)

        advanceUntilIdle()

        val state = vm.day.value
        assertTrue(state is UiState.Success)
        assertEquals("2026-06-16", (state as UiState.Success).data.date)
    }

    @Test
    fun `addEntry posts then reloads the day`() = runTest {
        val repo = FakeLogRepository()
        val vm = DiaryViewModel(repo, fakeApi)
        advanceUntilIdle()

        vm.addEntry("food-1", "lunch", 150.0, "g")
        advanceUntilIdle()

        assertEquals(1, repo.added)
        assertTrue(vm.day.value is UiState.Success)
    }

    @Test
    fun `deleteEntry removes then reloads`() = runTest {
        val repo = FakeLogRepository()
        val vm = DiaryViewModel(repo, fakeApi)
        advanceUntilIdle()

        vm.deleteEntry("e1")
        advanceUntilIdle()

        assertEquals(1, repo.deleted)
        assertTrue(vm.day.value is UiState.Success)
    }

    @Test
    fun `updateEntry edits then reloads`() = runTest {
        val repo = FakeLogRepository()
        val vm = DiaryViewModel(repo, fakeApi)
        advanceUntilIdle()

        vm.updateEntry("e1", 200.0, "dinner")
        advanceUntilIdle()

        assertEquals(1, repo.updated)
        assertTrue(vm.day.value is UiState.Success)
    }

    @Test
    fun `quickAdd posts raw macros then reloads`() = runTest {
        val repo = FakeLogRepository()
        val vm = DiaryViewModel(repo, fakeApi)
        advanceUntilIdle()

        vm.quickAdd("snack", "Shake", 200.0, 30.0, 10.0, 3.0)
        advanceUntilIdle()

        assertEquals(1, repo.quickAdded)
        assertTrue(vm.day.value is UiState.Success)
    }

    @Test
    fun `trained-today flag surfaces in the loaded day`() = runTest {
        val repo = FakeLogRepository(day = dailyLog("2026-06-16", trainedToday = true))
        val vm = DiaryViewModel(repo, fakeApi)

        advanceUntilIdle()

        val state = vm.day.value
        assertTrue(state is UiState.Success)
        assertTrue((state as UiState.Success).data.trainedToday)
    }

    @Test
    fun `load failure emits Error`() = runTest {
        val repo = FakeLogRepository(failWith = RuntimeException("offline"))
        val vm = DiaryViewModel(repo, fakeApi)

        advanceUntilIdle()

        val state = vm.day.value
        assertTrue(state is UiState.Error)
        assertEquals("offline", (state as UiState.Error).message)
    }
}
