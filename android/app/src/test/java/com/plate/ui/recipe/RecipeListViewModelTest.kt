package com.plate.ui.recipe

import com.plate.data.remote.DailyLog
import com.plate.data.remote.LogEntryOut
import com.plate.data.remote.RecipeCreate
import com.plate.data.remote.RecipeItemIn
import com.plate.data.remote.RecipeOut
import com.plate.data.remote.TotalsOut
import com.plate.data.repository.LogRepository
import com.plate.data.repository.RecipeRepository
import com.plate.util.MainDispatcherRule
import com.plate.util.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private fun recipe(id: String, name: String) =
    RecipeOut(id, name, null, emptyList(), TotalsOut(300.0, 20.0, 30.0, 10.0))

private class FakeRecipeRepository(
    var recipes: MutableList<RecipeOut> = mutableListOf(recipe("r1", "Oat Bowl")),
    private val failWith: Exception? = null,
) : RecipeRepository {
    var deleted = 0
    override suspend fun list(): List<RecipeOut> {
        failWith?.let { throw it }
        return recipes
    }
    override suspend fun get(id: String): RecipeOut = recipes.first { it.id == id }
    override suspend fun create(name: String, description: String?, items: List<RecipeItemIn>): RecipeOut =
        recipe("new", name).also { recipes.add(it) }
    override suspend fun rename(id: String, name: String?, description: String?): RecipeOut = recipes.first { it.id == id }
    override suspend fun replaceItems(id: String, items: List<RecipeItemIn>): RecipeOut = recipes.first { it.id == id }
    override suspend fun delete(id: String) {
        deleted++
        recipes.removeAll { it.id == id }
    }
}

private class FakeLogRepository(var loggedRecipe: String? = null, var loggedMeal: String? = null) : LogRepository {
    override suspend fun getDay(date: String): DailyLog = throw IllegalStateException()
    override suspend fun addEntry(foodId: String, date: String, meal: String, quantity: Double, unit: String) =
        throw IllegalStateException()
    override suspend fun updateEntry(id: String, quantity: Double?, unit: String?, meal: String?) =
        throw IllegalStateException()
    override suspend fun deleteEntry(id: String) = throw IllegalStateException()
    override suspend fun quickAdd(date: String, meal: String, name: String?, kcal: Double, proteinG: Double, carbsG: Double, fatG: Double) =
        throw IllegalStateException()
    override suspend fun logRecipe(recipeId: String, date: String, meal: String): List<LogEntryOut> {
        loggedRecipe = recipeId
        loggedMeal = meal
        return listOf(LogEntryOut("e", "f", "Food", date, meal, 1.0, "serving", 300.0, 20.0, 30.0, 10.0))
    }

    override suspend fun syncPending() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeListViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `init loads recipes into Success`() = runTest {
        val vm = RecipeListViewModel(FakeRecipeRepository(), FakeLogRepository())
        advanceUntilIdle()
        val state = vm.recipes.value
        assertTrue(state is UiState.Success)
        assertEquals(1, (state as UiState.Success).data.size)
    }

    @Test
    fun `delete removes then reloads`() = runTest {
        val repo = FakeRecipeRepository()
        val vm = RecipeListViewModel(repo, FakeLogRepository())
        advanceUntilIdle()

        vm.delete("r1")
        advanceUntilIdle()

        assertEquals(1, repo.deleted)
        assertEquals(0, (vm.recipes.value as UiState.Success).data.size)
    }

    @Test
    fun `logToday logs the recipe to the chosen meal and sets a message`() = runTest {
        val logRepo = FakeLogRepository()
        val vm = RecipeListViewModel(FakeRecipeRepository(), logRepo)
        advanceUntilIdle()

        vm.logToday(recipe("r1", "Oat Bowl"), "breakfast")
        advanceUntilIdle()

        assertEquals("r1", logRepo.loggedRecipe)
        assertEquals("breakfast", logRepo.loggedMeal)
        assertNotNull(vm.message.value)
    }

    @Test
    fun `load failure emits Error`() = runTest {
        val vm = RecipeListViewModel(
            FakeRecipeRepository(failWith = RuntimeException("offline")),
            FakeLogRepository(),
        )
        advanceUntilIdle()
        assertTrue(vm.recipes.value is UiState.Error)
    }
}
