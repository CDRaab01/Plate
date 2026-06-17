package com.plate.ui.photo

import com.plate.data.remote.DailyLog
import com.plate.data.remote.FoodCreateRequest
import com.plate.data.remote.FoodOut
import com.plate.data.remote.LogEntryCreate
import com.plate.data.remote.LogEntryOut
import com.plate.data.remote.PhotoEstimateItem
import com.plate.data.remote.PhotoEstimateResponse
import com.plate.data.repository.FoodRepository
import com.plate.data.repository.LogRepository
import com.plate.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private fun item(
    name: String = "Grilled chicken",
    grams: Double = 150.0,
    kcal: Double = 250.0,
    confidence: Double = 0.8,
) = PhotoEstimateItem(
    name = name,
    estGrams = grams,
    kcal = kcal,
    proteinG = 46.0,
    carbsG = 0.0,
    fatG = 6.0,
    confidence = confidence,
)

private class FakeFoodRepository(
    private val estimate: PhotoEstimateResponse? = null,
    private val estimateError: Exception? = null,
    private val createError: Exception? = null,
) : FoodRepository {
    var analyzeCount = 0
    var lastCreate: FoodCreateRequest? = null

    override suspend fun search(query: String) = emptyList<FoodOut>()
    override suspend fun lookupBarcode(code: String): FoodOut = throw IllegalStateException()

    override suspend fun createFood(req: FoodCreateRequest): FoodOut {
        createError?.let { throw it }
        lastCreate = req
        return FoodOut(
            id = "food-1",
            source = "user",
            name = req.name,
            kcalPer100g = req.kcalPer100g,
            proteinGPer100g = req.proteinGPer100g,
            carbsGPer100g = req.carbsGPer100g,
            fatGPer100g = req.fatGPer100g,
        )
    }

    override suspend fun estimatePhoto(image: ByteArray, mimeType: String): PhotoEstimateResponse {
        analyzeCount++
        estimateError?.let { throw it }
        return estimate ?: PhotoEstimateResponse(emptyList(), lowConfidence = true)
    }
}

private class FakeLogRepository : LogRepository {
    var lastEntry: LogEntryCreate? = null

    override suspend fun getDay(date: String): DailyLog = throw IllegalStateException()

    override suspend fun addEntry(
        foodId: String,
        date: String,
        meal: String,
        quantity: Double,
        unit: String,
    ): LogEntryOut {
        lastEntry = LogEntryCreate(foodId, date, meal, quantity, unit)
        return LogEntryOut(
            id = "entry-1",
            foodId = foodId,
            date = date,
            meal = meal,
            quantity = quantity,
            unit = unit,
            kcal = 250.0,
            proteinG = 46.0,
            carbsG = 0.0,
            fatG = 6.0,
        )
    }

    override suspend fun updateEntry(id: String, quantity: Double?, unit: String?, meal: String?) =
        throw IllegalStateException()

    override suspend fun deleteEntry(id: String) = throw IllegalStateException()

    override suspend fun quickAdd(
        date: String,
        meal: String,
        name: String?,
        kcal: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
    ): LogEntryOut = throw IllegalStateException()

    override suspend fun logRecipe(recipeId: String, date: String, meal: String) =
        throw IllegalStateException()
}

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoLogViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `analyze populates editable drafts`() = runTest {
        val food = FakeFoodRepository(
            estimate = PhotoEstimateResponse(listOf(item(), item("Rice", kcal = 200.0)), false),
        )
        val vm = PhotoLogViewModel(food, FakeLogRepository())

        vm.analyze(byteArrayOf(1, 2, 3), "image/jpeg")
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.analyzed)
        assertFalse(state.analyzing)
        assertEquals(2, state.drafts.size)
        assertEquals("Grilled chicken", state.drafts[0].name)
        // Stable, distinct ids so per-card edit state and logging track correctly.
        assertEquals(listOf(0, 1), state.drafts.map { it.id })
    }

    @Test
    fun `low confidence estimate is surfaced on the draft`() = runTest {
        val food = FakeFoodRepository(
            estimate = PhotoEstimateResponse(listOf(item(confidence = 0.2)), lowConfidence = true, note = "check"),
        )
        val vm = PhotoLogViewModel(food, FakeLogRepository())

        vm.analyze(byteArrayOf(1), "image/jpeg")
        advanceUntilIdle()

        assertTrue(vm.state.value.lowConfidence)
        assertEquals("check", vm.state.value.note)
        assertTrue(vm.state.value.drafts[0].lowConfidence)
    }

    @Test
    fun `empty estimate yields no drafts but keeps the note`() = runTest {
        val food = FakeFoodRepository(
            estimate = PhotoEstimateResponse(emptyList(), lowConfidence = true, note = "No food found"),
        )
        val vm = PhotoLogViewModel(food, FakeLogRepository())

        vm.analyze(byteArrayOf(1), "image/jpeg")
        advanceUntilIdle()

        assertTrue(vm.state.value.analyzed)
        assertTrue(vm.state.value.drafts.isEmpty())
        assertEquals("No food found", vm.state.value.note)
    }

    @Test
    fun `analyze failure surfaces a friendly error`() = runTest {
        val food = FakeFoodRepository(estimateError = RuntimeException("LM Studio is not reachable"))
        val vm = PhotoLogViewModel(food, FakeLogRepository())

        vm.analyze(byteArrayOf(1), "image/jpeg")
        advanceUntilIdle()

        assertEquals("LM Studio is not reachable", vm.state.value.error)
        assertFalse(vm.state.value.analyzed)
    }

    @Test
    fun `confirming a draft creates the food then logs the portion`() = runTest {
        val food = FakeFoodRepository(estimate = PhotoEstimateResponse(listOf(item()), false))
        val logRepo = FakeLogRepository()
        val vm = PhotoLogViewModel(food, logRepo)

        vm.analyze(byteArrayOf(1), "image/jpeg")
        advanceUntilIdle()

        var reloaded = false
        vm.logDraft(vm.state.value.drafts[0], meal = "lunch", date = "2026-06-16") { reloaded = true }
        advanceUntilIdle()

        // Custom food stored with per-100g macros derived from the portion (250 kcal / 150 g → 166.67).
        val created = food.lastCreate!!
        assertEquals("Grilled chicken", created.name)
        assertEquals(150.0, created.servingSize!!, 0.001)
        assertEquals(250.0 * 100.0 / 150.0, created.kcalPer100g, 0.001)

        // Logged as the portion in grams into the chosen meal.
        val entry = logRepo.lastEntry!!
        assertEquals("food-1", entry.foodId)
        assertEquals("lunch", entry.meal)
        assertEquals(150.0, entry.quantity, 0.001)
        assertEquals("g", entry.unit)

        assertTrue(vm.state.value.drafts[0].logged)
        assertTrue(reloaded)
    }

    @Test
    fun `a logged draft is not logged again`() = runTest {
        val food = FakeFoodRepository(estimate = PhotoEstimateResponse(listOf(item()), false))
        val logRepo = FakeLogRepository()
        val vm = PhotoLogViewModel(food, logRepo)

        vm.analyze(byteArrayOf(1), "image/jpeg")
        advanceUntilIdle()
        vm.logDraft(vm.state.value.drafts[0], "lunch", "2026-06-16") {}
        advanceUntilIdle()
        logRepo.lastEntry = null

        // The draft is now logged; a second confirm must be a no-op.
        vm.logDraft(vm.state.value.drafts[0], "dinner", "2026-06-16") {}
        advanceUntilIdle()

        assertNull(logRepo.lastEntry)
    }

    @Test
    fun `log failure surfaces an error and leaves the draft unlogged`() = runTest {
        val food = FakeFoodRepository(
            estimate = PhotoEstimateResponse(listOf(item()), false),
            createError = RuntimeException("network down"),
        )
        val vm = PhotoLogViewModel(food, FakeLogRepository())

        vm.analyze(byteArrayOf(1), "image/jpeg")
        advanceUntilIdle()
        vm.logDraft(vm.state.value.drafts[0], "lunch", "2026-06-16") {}
        advanceUntilIdle()

        assertEquals("network down", vm.state.value.error)
        assertFalse(vm.state.value.drafts[0].logged)
        assertFalse(vm.state.value.drafts[0].logging)
    }

    @Test
    fun `per100g returns zero for a zero-gram portion`() {
        assertEquals(0.0, PhotoLogViewModel.per100g(250.0, 0.0), 0.0)
        assertEquals(200.0, PhotoLogViewModel.per100g(100.0, 50.0), 0.001)
    }
}
