package com.plate.ui.recipe

import com.plate.data.remote.FoodCreateRequest
import com.plate.data.remote.FoodOut
import com.plate.data.remote.PhotoEstimateResponse
import com.plate.data.remote.RecipeCreate
import com.plate.data.remote.RecipeItemIn
import com.plate.data.remote.RecipeOut
import com.plate.data.remote.TotalsOut
import com.plate.data.repository.FoodRepository
import com.plate.data.repository.RecipeRepository
import com.plate.util.MainDispatcherRule
import com.plate.util.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private fun food(id: String, name: String) =
    FoodOut(id = id, source = "user", name = name, kcalPer100g = 100.0, proteinGPer100g = 5.0, carbsGPer100g = 10.0, fatGPer100g = 2.0)

private class EditFakeRecipeRepository : RecipeRepository {
    var created: RecipeCreate? = null
    override suspend fun list(): List<RecipeOut> = emptyList()
    override suspend fun get(id: String): RecipeOut =
        RecipeOut(id, "Existing", null, emptyList(), TotalsOut(0.0, 0.0, 0.0, 0.0))
    override suspend fun create(name: String, description: String?, items: List<RecipeItemIn>): RecipeOut {
        created = RecipeCreate(name, description, items)
        return RecipeOut("new", name, description, emptyList(), TotalsOut(0.0, 0.0, 0.0, 0.0))
    }
    override suspend fun rename(id: String, name: String?, description: String?): RecipeOut =
        RecipeOut(id, name ?: "", description, emptyList(), TotalsOut(0.0, 0.0, 0.0, 0.0))
    override suspend fun replaceItems(id: String, items: List<RecipeItemIn>): RecipeOut =
        RecipeOut(id, "Existing", null, emptyList(), TotalsOut(0.0, 0.0, 0.0, 0.0))
    override suspend fun delete(id: String) {}
    override suspend fun discover(query: String) = emptyList<com.plate.data.remote.DiscoveredRecipe>()
    override suspend fun importRecipe(sourceId: String): RecipeOut =
        RecipeOut(sourceId, "Imported", null, emptyList(), TotalsOut(0.0, 0.0, 0.0, 0.0))
}

private class EditFakeFoodRepository(private val results: List<FoodOut>) : FoodRepository {
    override suspend fun recentFoods(limit: Int) = emptyList<com.plate.data.remote.RecentFoodOut>()

    override suspend fun search(query: String, filter: String?): List<FoodOut> = results
    override suspend fun lookupBarcode(code: String): com.plate.data.remote.FoodDetailOut =
        throw IllegalStateException()

    override suspend fun getFood(id: String): com.plate.data.remote.FoodDetailOut =
        throw IllegalStateException()
    override suspend fun createFood(req: FoodCreateRequest): FoodOut = throw IllegalStateException()
    override suspend fun estimatePhoto(image: ByteArray, mimeType: String): PhotoEstimateResponse =
        throw IllegalStateException()

    override suspend fun estimateLabel(image: ByteArray, mimeType: String): PhotoEstimateResponse =
        throw IllegalStateException()

    override suspend fun parseVoice(text: String): PhotoEstimateResponse =
        throw IllegalStateException()
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeEditViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `save without a name emits Error`() = runTest {
        val vm = RecipeEditViewModel(EditFakeRecipeRepository(), EditFakeFoodRepository(emptyList()))
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.saveState.value is UiState.Error)
    }

    @Test
    fun `search populates results then addItem adds to the draft`() = runTest {
        val vm = RecipeEditViewModel(EditFakeRecipeRepository(), EditFakeFoodRepository(listOf(food("f1", "Oats"))))
        vm.setQuery("oats")
        vm.search()
        advanceUntilIdle()
        assertTrue(vm.results.value is UiState.Success)

        vm.addItem(food("f1", "Oats"))
        assertEquals(1, vm.items.value.size)
    }

    @Test
    fun `save creates a recipe with its items`() = runTest {
        val repo = EditFakeRecipeRepository()
        val vm = RecipeEditViewModel(repo, EditFakeFoodRepository(emptyList()))
        vm.setName("Breakfast")
        vm.addItem(food("f1", "Oats"))
        vm.addItem(food("f2", "Banana"))

        vm.save()
        advanceUntilIdle()

        assertTrue(vm.saveState.value is UiState.Success)
        assertEquals("Breakfast", repo.created?.name)
        assertEquals(2, repo.created?.items?.size)
    }

    @Test
    fun `removeItem drops the item`() = runTest {
        val vm = RecipeEditViewModel(EditFakeRecipeRepository(), EditFakeFoodRepository(emptyList()))
        vm.addItem(food("f1", "Oats"))
        vm.addItem(food("f2", "Banana"))
        vm.removeItem(0)
        assertEquals(1, vm.items.value.size)
        assertEquals("Banana", vm.items.value[0].food.name)
    }
}
