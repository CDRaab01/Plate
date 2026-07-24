package com.plate.ui.diary

import com.plate.data.remote.ApiService
import com.plate.data.remote.DailyLog
import com.plate.data.remote.LogEntryOut
import com.plate.data.remote.MealGroup
import com.plate.data.remote.TotalsOut
import com.plate.data.repository.BatchLogItem
import com.plate.data.repository.LogRepository
import com.plate.data.repository.Stale
import com.plate.util.MainDispatcherRule
import com.plate.util.PendingDiaryDate
import com.plate.util.UiState
import org.mockito.kotlin.mock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

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
    /** Non-null = the day is served as if from the offline cache, captured at this time. */
    private val dayAsOfMs: Long? = null,
) : LogRepository {
    var added = 0
    var deleted = 0
    var updated = 0

    override suspend fun getDay(date: String): DailyLog {
        failWith?.let { throw it }
        return day
    }

    override suspend fun getDayStale(date: String) = Stale(getDay(date), dayAsOfMs)

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

    var batchAdded = 0
    var lastBatchMeal: String? = null
    var lastBatchSize = 0
    override suspend fun addEntries(
        date: String,
        meal: String,
        items: List<BatchLogItem>,
    ): List<LogEntryOut> {
        batchAdded++
        lastBatchMeal = meal
        lastBatchSize = items.size
        return items.map {
            LogEntryOut("e", it.foodId, "Food", date, meal, it.quantity, it.unit, 100.0, 5.0, 10.0, 2.0)
        }
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

    var copiedFrom: String? = null
    var copiedTo: String? = null
    override suspend fun copyDay(fromDate: String, toDate: String): List<LogEntryOut> {
        copiedFrom = fromDate
        copiedTo = toDate
        return emptyList()
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
    fun `addEntries batch-logs the selection to one meal then reloads and calls onDone`() = runTest {
        val repo = FakeLogRepository()
        val vm = DiaryViewModel(repo, fakeApi)
        advanceUntilIdle()

        var done = false
        vm.addEntries(
            listOf(BatchLogItem("f1", 1.0, "serving"), BatchLogItem("f2", 100.0, "g")),
            "dinner",
        ) { done = true }
        advanceUntilIdle()

        assertEquals(1, repo.batchAdded)
        assertEquals("dinner", repo.lastBatchMeal)
        assertEquals(2, repo.lastBatchSize)
        assertTrue(done)
        assertTrue(vm.day.value is UiState.Success)
    }

    @Test
    fun `addEntries with an empty selection is a no-op`() = runTest {
        val repo = FakeLogRepository()
        val vm = DiaryViewModel(repo, fakeApi)
        advanceUntilIdle()

        var done = false
        vm.addEntries(emptyList(), "lunch") { done = true }
        advanceUntilIdle()

        assertEquals(0, repo.batchAdded)
        assertTrue(!done)
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
    fun `copyYesterday copies the previous day into the current day then reloads`() = runTest {
        val repo = FakeLogRepository(day = dailyLog("2026-06-16"))
        val vm = DiaryViewModel(repo, fakeApi)
        advanceUntilIdle()
        val today = vm.date.value

        vm.copyYesterday()
        advanceUntilIdle()

        assertEquals(LocalDate.parse(today).minusDays(1).toString(), repo.copiedFrom)
        assertEquals(today, repo.copiedTo)
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
    fun `prevDay moves the date back a day and reloads`() = runTest {
        val repo = FakeLogRepository()
        val vm = DiaryViewModel(repo, fakeApi)
        advanceUntilIdle()
        val start = LocalDate.parse(vm.date.value)

        vm.prevDay()
        advanceUntilIdle()

        assertEquals(start.minusDays(1).toString(), vm.date.value)
        assertTrue(vm.day.value is UiState.Success)
    }

    @Test
    fun `nextDay is capped at today`() = runTest {
        val repo = FakeLogRepository()
        val vm = DiaryViewModel(repo, fakeApi)
        advanceUntilIdle()
        val today = vm.date.value

        // Already on today → next is a no-op.
        vm.nextDay()
        advanceUntilIdle()
        assertEquals(today, vm.date.value)

        // Step back, then forward returns to today.
        vm.prevDay()
        advanceUntilIdle()
        vm.nextDay()
        advanceUntilIdle()
        assertEquals(today, vm.date.value)
    }

    @Test
    fun `goToToday returns to the current date`() = runTest {
        val repo = FakeLogRepository()
        val vm = DiaryViewModel(repo, fakeApi)
        advanceUntilIdle()

        vm.prevDay()
        vm.prevDay()
        advanceUntilIdle()
        vm.goToToday()
        advanceUntilIdle()

        assertEquals(LocalDate.now().toString(), vm.date.value)
    }

    @Test
    fun `pending date from calendar jumps the diary to that day and is consumed`() = runTest {
        val pending = PendingDiaryDate()
        pending.request("2026-06-10")
        val repo = FakeLogRepository()
        val vm = DiaryViewModel(repo, fakeApi, pending)

        advanceUntilIdle()

        assertEquals("2026-06-10", vm.date.value)
        assertNull(pending.date.value)
        assertTrue(vm.day.value is UiState.Success)
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

    @Test
    fun `unreachable server with no cache reads as a plain server-unreachable error`() = runTest {
        val repo = FakeLogRepository(failWith = java.io.IOException("Unable to resolve host"))
        val vm = DiaryViewModel(repo, fakeApi)

        advanceUntilIdle()

        val state = vm.day.value
        assertTrue(state is UiState.Error)
        assertEquals("Can't reach the Plate server", (state as UiState.Error).message)
        assertNull(vm.staleAsOfMs.value)
    }

    @Test
    fun `a cache-served day surfaces its capture time for the stale banner`() = runTest {
        val repo = FakeLogRepository(day = dailyLog("2026-06-16"), dayAsOfMs = 42_000L)
        val vm = DiaryViewModel(repo, fakeApi)

        advanceUntilIdle()

        assertTrue(vm.day.value is UiState.Success)
        assertEquals(42_000L, vm.staleAsOfMs.value)
    }

    @Test
    fun `a fresh day clears the stale banner`() = runTest {
        val repo = FakeLogRepository(day = dailyLog("2026-06-16"))
        val vm = DiaryViewModel(repo, fakeApi)

        advanceUntilIdle()

        assertTrue(vm.day.value is UiState.Success)
        assertNull(vm.staleAsOfMs.value)
    }
}
