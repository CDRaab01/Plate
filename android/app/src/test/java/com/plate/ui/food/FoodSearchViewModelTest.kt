package com.plate.ui.food

import com.plate.data.remote.FoodCreateRequest
import com.plate.data.remote.FoodOut
import com.plate.data.remote.PhotoEstimateResponse
import com.plate.data.repository.FoodRepository
import com.plate.util.MainDispatcherRule
import com.plate.util.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

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
) : FoodRepository {
    var searchCount = 0
    var lastQuery: String? = null

    override suspend fun search(query: String): List<FoodOut> {
        searchCount++
        lastQuery = query
        failWith?.let { throw it }
        return results
    }

    override suspend fun lookupBarcode(code: String): FoodOut =
        results.firstOrNull() ?: throw IllegalStateException("no result")

    override suspend fun createFood(req: FoodCreateRequest): FoodOut =
        throw IllegalStateException("not used")

    override suspend fun estimatePhoto(image: ByteArray, mimeType: String): PhotoEstimateResponse =
        throw IllegalStateException("not used")
}

@OptIn(ExperimentalCoroutinesApi::class)
class FoodSearchViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `query change emits Success with results`() = runTest {
        val repo = FakeFoodRepository(results = listOf(food("1", "Banana")))
        val vm = FoodSearchViewModel(repo)

        vm.onQueryChange("banana")
        advanceUntilIdle()

        val state = vm.results.value
        assertTrue(state is UiState.Success)
        assertEquals(listOf(food("1", "Banana")), (state as UiState.Success).data)
        assertEquals("banana", repo.lastQuery)
    }

    @Test
    fun `blank query resets to Idle without searching`() = runTest {
        val repo = FakeFoodRepository()
        val vm = FoodSearchViewModel(repo)

        vm.onQueryChange("   ")
        advanceUntilIdle()

        assertEquals(UiState.Idle, vm.results.value)
        assertEquals(0, repo.searchCount)
    }

    @Test
    fun `rapid typing debounces to a single search`() = runTest {
        val repo = FakeFoodRepository(results = listOf(food("1", "Chicken")))
        val vm = FoodSearchViewModel(repo)

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
        val vm = FoodSearchViewModel(repo)

        vm.onQueryChange("banana")
        advanceUntilIdle()

        val state = vm.results.value
        assertTrue(state is UiState.Error)
        assertEquals("network down", (state as UiState.Error).message)
    }
}
