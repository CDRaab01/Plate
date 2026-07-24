package com.plate.ui.food

import com.plate.data.remote.FoodCreateRequest
import com.plate.data.remote.FoodDetailOut
import com.plate.data.remote.FoodOut
import com.plate.data.remote.PhotoEstimateResponse
import com.plate.data.remote.PortionOut
import com.plate.data.remote.RecentFoodOut
import com.plate.data.repository.FoodRepository
import com.plate.util.AppPreferences
import com.plate.util.MainDispatcherRule
import com.plate.util.UiState
import com.plate.util.UnitSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private fun food(id: String, name: String) = FoodOut(
    id = id,
    source = "usda",
    name = name,
    kcalPer100g = 100.0,
    proteinGPer100g = 5.0,
    carbsGPer100g = 10.0,
    fatGPer100g = 2.0,
)

private class FakeFoodRepository(
    private val results: List<FoodOut> = emptyList(),
    private val failWith: Exception? = null,
    private val recent: List<RecentFoodOut> = emptyList(),
    private val detail: FoodDetailOut? = null,
) : FoodRepository {
    var searchCount = 0
    var lastQuery: String? = null
    var lastFilter: String? = null
    var detailCount = 0

    override suspend fun recentFoods(limit: Int) = recent

    override suspend fun search(query: String, filter: String?): List<FoodOut> {
        searchCount++
        lastQuery = query
        lastFilter = filter
        failWith?.let { throw it }
        return results
    }

    override suspend fun getFood(id: String): FoodDetailOut {
        detailCount++
        return detail ?: throw IllegalStateException("no detail")
    }

    override suspend fun lookupBarcode(code: String): FoodDetailOut =
        throw IllegalStateException("not used")

    override suspend fun createFood(req: FoodCreateRequest): FoodOut =
        throw IllegalStateException("not used")

    override suspend fun estimatePhoto(image: ByteArray, mimeType: String): PhotoEstimateResponse =
        throw IllegalStateException("not used")

    override suspend fun estimateLabel(image: ByteArray, mimeType: String): PhotoEstimateResponse =
        throw IllegalStateException("not used")

    override suspend fun parseVoice(text: String): PhotoEstimateResponse =
        throw IllegalStateException("not used")
}

@OptIn(ExperimentalCoroutinesApi::class)
class FoodSearchViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private fun prefs(): AppPreferences =
        mock { whenever(it.unitSystem).thenReturn(flowOf(UnitSystem.IMPERIAL)) }

    @Test
    fun `query change emits Success with results`() = runTest {
        val repo = FakeFoodRepository(results = listOf(food("1", "Banana")))
        val vm = FoodSearchViewModel(repo, prefs())

        vm.onQueryChange("banana")
        advanceUntilIdle()

        val state = vm.results.value
        assertTrue(state is UiState.Success)
        assertEquals(listOf(food("1", "Banana")), (state as UiState.Success).data)
        assertEquals("banana", repo.lastQuery)
    }

    @Test
    fun `recent foods load on init for the empty-query surface`() = runTest {
        val recent = RecentFoodOut(
            food = food("1", "Eggs"),
            lastMeal = "breakfast",
            lastQuantity = 2.0,
            lastUnit = "serving",
        )
        val vm = FoodSearchViewModel(FakeFoodRepository(recent = listOf(recent)), prefs())
        advanceUntilIdle()

        assertEquals(listOf(recent), vm.recent.value)
    }

    @Test
    fun `blank query resets to Idle without searching`() = runTest {
        val repo = FakeFoodRepository()
        val vm = FoodSearchViewModel(repo, prefs())

        vm.onQueryChange("   ")
        advanceUntilIdle()

        assertEquals(UiState.Idle, vm.results.value)
        assertEquals(0, repo.searchCount)
    }

    @Test
    fun `rapid typing debounces to a single search`() = runTest {
        val repo = FakeFoodRepository(results = listOf(food("1", "Chicken")))
        val vm = FoodSearchViewModel(repo, prefs())

        vm.onQueryChange("c")
        vm.onQueryChange("ch")
        vm.onQueryChange("chi")
        vm.onQueryChange("chicken")
        advanceUntilIdle()

        // Earlier keystrokes are cancelled before their delay elapses.
        assertEquals(1, repo.searchCount)
        assertEquals("chicken", repo.lastQuery)
    }

    @Test
    fun `search failure emits Error`() = runTest {
        val repo = FakeFoodRepository(failWith = RuntimeException("network down"))
        val vm = FoodSearchViewModel(repo, prefs())

        vm.onQueryChange("banana")
        advanceUntilIdle()

        val state = vm.results.value
        assertTrue(state is UiState.Error)
        assertEquals("network down", (state as UiState.Error).message)
    }

    @Test
    fun `filter change re-issues the current query immediately with the filter wire value`() = runTest {
        val repo = FakeFoodRepository(results = listOf(food("1", "Chicken")))
        val vm = FoodSearchViewModel(repo, prefs())

        vm.onQueryChange("chicken")
        advanceUntilIdle()
        assertEquals("all", repo.lastFilter)

        vm.onFilterChange(SearchFilter.GENERIC)
        advanceUntilIdle()

        assertEquals(2, repo.searchCount)
        assertEquals("generic", repo.lastFilter)
        assertEquals("chicken", repo.lastQuery)
    }

    @Test
    fun `filter change with a blank query does not search`() = runTest {
        val repo = FakeFoodRepository()
        val vm = FoodSearchViewModel(repo, prefs())

        vm.onFilterChange(SearchFilter.MINE)
        advanceUntilIdle()

        assertEquals(0, repo.searchCount)
    }

    @Test
    fun `loadFoodDetail emits Success with portions`() = runTest {
        val detail = FoodDetailOut(
            id = "1",
            source = "usda",
            name = "Banana",
            kcalPer100g = 89.0,
            proteinGPer100g = 1.1,
            carbsGPer100g = 22.8,
            fatGPer100g = 0.3,
            portions = listOf(PortionOut("p1", "1 cup, sliced", 150.0)),
        )
        val repo = FakeFoodRepository(detail = detail)
        val vm = FoodSearchViewModel(repo, prefs())

        vm.loadFoodDetail("1")
        advanceUntilIdle()

        val state = vm.selectedDetail.value
        assertTrue(state is UiState.Success)
        assertEquals(detail, (state as UiState.Success).data)
        assertEquals(1, repo.detailCount)
    }

    @Test
    fun `loadFoodDetail failure is a non-fatal Error state`() = runTest {
        val repo = FakeFoodRepository() // no detail configured -> throws
        val vm = FoodSearchViewModel(repo, prefs())

        vm.loadFoodDetail("1")
        advanceUntilIdle()

        assertTrue(vm.selectedDetail.value is UiState.Error)
    }
}
