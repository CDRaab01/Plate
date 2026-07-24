package com.plate.ui.recipe

import com.plate.data.remote.DiscoveredRecipe
import com.plate.data.remote.LogEntryOut
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
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private fun recipeOut(id: String, name: String) =
    RecipeOut(id = id, name = name, description = null, items = emptyList(), totals = TotalsOut(0.0, 0.0, 0.0, 0.0))

private open class FakeRecipeRepo : RecipeRepository {
    var discoverResult: List<DiscoveredRecipe> = emptyList()
    var discoverError: Exception? = null
    var imported: RecipeOut? = null
    override suspend fun list() = emptyList<RecipeOut>()
    override suspend fun get(id: String) = error("unused")
    override suspend fun create(name: String, description: String?, items: List<RecipeItemIn>) = error("unused")
    override suspend fun rename(id: String, name: String?, description: String?) = error("unused")
    override suspend fun replaceItems(id: String, items: List<RecipeItemIn>) = error("unused")
    override suspend fun delete(id: String) {}
    override suspend fun discover(query: String): List<DiscoveredRecipe> {
        discoverError?.let { throw it }
        return discoverResult
    }
    override suspend fun importRecipe(sourceId: String): RecipeOut =
        imported ?: recipeOut("r1", "Imported")
}

private class FakeLogRepo : LogRepository {
    var loggedTo: Pair<String, String>? = null // recipeId, meal
    override suspend fun getDay(date: String) = error("unused")
    override suspend fun addEntry(foodId: String, date: String, meal: String, quantity: Double, unit: String, portionId: String?) = error("unused")
    override suspend fun updateEntry(id: String, quantity: Double?, unit: String?, meal: String?) = error("unused")
    override suspend fun deleteEntry(id: String) {}
    override suspend fun quickAdd(date: String, meal: String, name: String?, kcal: Double, proteinG: Double, carbsG: Double, fatG: Double) = error("unused")
    override suspend fun copyDay(fromDate: String, toDate: String) = emptyList<com.plate.data.remote.LogEntryOut>()

    override suspend fun logRecipe(recipeId: String, date: String, meal: String): List<LogEntryOut> {
        loggedTo = recipeId to meal
        return emptyList()
    }
    override suspend fun syncPending() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverRecipesViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private fun vm(repo: FakeRecipeRepo = FakeRecipeRepo(), log: FakeLogRepo = FakeLogRepo()) =
        DiscoverRecipesViewModel(repo, log)

    @Test
    fun `search maps results into Success`() = runTest {
        val repo = FakeRecipeRepo().apply {
            discoverResult = listOf(DiscoveredRecipe("9", "Chicken Rice", servings = 3))
        }
        val model = vm(repo)
        model.setQuery("chicken")
        model.search()
        advanceUntilIdle()

        val s = model.results.value
        assertTrue(s is UiState.Success)
        assertEquals("Chicken Rice", (s as UiState.Success).data.single().title)
    }

    @Test
    fun `blank query does not search`() = runTest {
        val model = vm()
        model.setQuery("   ")
        model.search()
        advanceUntilIdle()
        assertTrue(model.results.value is UiState.Idle)
    }

    @Test
    fun `503 surfaces a friendly not-configured message`() = runTest {
        val repo = FakeRecipeRepo().apply {
            discoverError = HttpException(Response.error<Any>(503, "".toResponseBody()))
        }
        val model = vm(repo)
        model.setQuery("chicken")
        model.search()
        advanceUntilIdle()

        val s = model.results.value
        assertTrue(s is UiState.Error)
        assertTrue((s as UiState.Error).message.contains("isn't set up"))
    }

    @Test
    fun `import sets the pending recipe for the log-or-save choice`() = runTest {
        val repo = FakeRecipeRepo().apply { imported = recipeOut("r5", "Test Bowl") }
        val model = vm(repo)
        model.import("123")
        advanceUntilIdle()
        assertEquals("Test Bowl", model.pending.value?.name)
    }

    @Test
    fun `logPendingTo logs the recipe then clears pending`() = runTest {
        val repo = FakeRecipeRepo().apply { imported = recipeOut("r5", "Test Bowl") }
        val log = FakeLogRepo()
        val model = vm(repo, log)
        model.import("123")
        advanceUntilIdle()

        model.logPendingTo("dinner")
        advanceUntilIdle()

        assertEquals("r5" to "dinner", log.loggedTo)
        assertNull(model.pending.value)
    }

    @Test
    fun `keepPendingSaved clears pending without logging`() = runTest {
        val repo = FakeRecipeRepo().apply { imported = recipeOut("r5", "Test Bowl") }
        val log = FakeLogRepo()
        val model = vm(repo, log)
        model.import("123")
        advanceUntilIdle()

        model.keepPendingSaved()

        assertNull(model.pending.value)
        assertNull(log.loggedTo)
    }
}
