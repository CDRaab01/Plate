package com.plate.ui.restaurant

import com.plate.data.remote.ComponentMacrosIn
import com.plate.data.remote.FoodCreateRequest
import com.plate.data.remote.FoodOut
import com.plate.data.remote.MenuParseComponent
import com.plate.data.remote.MenuParseResponse
import com.plate.data.remote.PhotoEstimateResponse
import com.plate.data.remote.RecentFoodOut
import com.plate.data.remote.RestaurantComponentIn
import com.plate.data.remote.RestaurantLogSelection
import com.plate.data.remote.RestaurantOut
import com.plate.data.repository.FoodRepository
import com.plate.data.repository.RestaurantRepository
import com.plate.util.MainDispatcherRule
import com.plate.util.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private fun parsedComponent(
    category: String,
    name: String,
    official: Boolean = false,
) = MenuParseComponent(
    category = category,
    name = name,
    source = if (official) "official" else "estimate",
    foodId = if (official) null else "food_x",
    foodName = if (official) null else "Generic $name",
    macros = if (official) ComponentMacrosIn(kcal = 180.0, proteinG = 32.0, carbsG = 0.0, fatG = 7.0) else null,
    quantity = if (official) 1.0 else 150.0,
    unit = if (official) "serving" else "g",
    kcal = 180.0,
    proteinG = 32.0,
    carbsG = 0.0,
    fatG = 7.0,
    confidence = if (official) 0.9 else 0.7,
)

private fun parse(vararg components: MenuParseComponent, name: String? = "Salsa Grille") =
    MenuParseResponse(restaurantName = name, menuUrl = "https://x.example/menu", components = components.toList())

private class EditFakeRestaurantRepository : RestaurantRepository {
    var parseResponse: MenuParseResponse = parse()
    var createdComponents: List<RestaurantComponentIn>? = null
    var createdShared: Boolean? = null
    var replacedComponents: List<RestaurantComponentIn>? = null

    override suspend fun list(): List<RestaurantOut> = emptyList()
    override suspend fun get(id: String): RestaurantOut = RestaurantOut(id = id, name = "X")
    override suspend fun create(
        name: String,
        menuUrl: String?,
        shared: Boolean,
        components: List<RestaurantComponentIn>,
    ): RestaurantOut {
        createdComponents = components
        createdShared = shared
        return RestaurantOut(id = "new", name = name)
    }

    override suspend fun update(id: String, name: String?, menuUrl: String?, shared: Boolean?) =
        RestaurantOut(id = id, name = name ?: "X")

    override suspend fun replaceComponents(id: String, components: List<RestaurantComponentIn>): RestaurantOut {
        replacedComponents = components
        return RestaurantOut(id = id, name = "X")
    }

    override suspend fun delete(id: String) {}
    var parsedUrl: String? = null
    var parsedText: String? = null
    override suspend fun parseMenu(url: String?, text: String?): MenuParseResponse {
        parsedUrl = url
        parsedText = text
        return parseResponse
    }
    override suspend fun log(id: String, date: String, meal: String, selections: List<RestaurantLogSelection>) = 0
}

private class EditFakeFoodRepository : FoodRepository {
    override suspend fun search(query: String): List<FoodOut> = emptyList()
    override suspend fun recentFoods(limit: Int): List<RecentFoodOut> = emptyList()
    override suspend fun lookupBarcode(code: String): FoodOut = throw IllegalStateException()
    override suspend fun createFood(req: FoodCreateRequest): FoodOut = throw IllegalStateException()
    override suspend fun estimatePhoto(image: ByteArray, mimeType: String): PhotoEstimateResponse =
        throw IllegalStateException()
    override suspend fun estimateLabel(image: ByteArray, mimeType: String): PhotoEstimateResponse =
        throw IllegalStateException()
    override suspend fun parseVoice(text: String): PhotoEstimateResponse = throw IllegalStateException()
}

@OptIn(ExperimentalCoroutinesApi::class)
class RestaurantEditViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    // ── Pure helpers ─────────────────────────────────────────────────────────

    @Test
    fun `groupByCategory preserves first-appearance order`() {
        val drafts = listOf(
            ComponentDraft(category = "Rice", name = "White"),
            ComponentDraft(category = "Protein", name = "Chicken"),
            ComponentDraft(category = "Rice", name = "Brown"),
        )
        val grouped = groupByCategory(drafts)
        assertEquals(listOf("Rice", "Protein"), grouped.keys.toList())
        assertEquals(listOf("White", "Brown"), grouped["Rice"]!!.map { it.name })
    }

    @Test
    fun `mergeParsedComponents appends new rows and keeps existing ones untouched`() {
        val existing = listOf(
            ComponentDraft(category = "Rice", name = "White Rice", quantity = 999.0),
        )
        val merged = mergeParsedComponents(
            existing,
            parse(
                parsedComponent("Rice", "white rice"), // dup (case-insensitive) — dropped
                parsedComponent("Protein", "Chicken", official = true),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals(999.0, merged[0].quantity, 0.001) // the manual edit survived
        assertEquals("Chicken", merged[1].name)
        assertEquals(180.0, merged[1].macros!!.kcal, 0.001)
    }

    @Test
    fun `official draft maps to a macros component, linked draft to a food id`() {
        val official = ComponentDraft(
            category = "Protein",
            name = "Chicken",
            macros = ComponentMacrosIn(kcal = 180.0, proteinG = 32.0, carbsG = 0.0, fatG = 7.0),
        ).toIn()
        assertNull(official.foodId)
        assertEquals(180.0, official.macros!!.kcal, 0.001)

        val linked = ComponentDraft(category = "Rice", name = "Rice", foodId = "f1").toIn()
        assertEquals("f1", linked.foodId)
        assertNull(linked.macros)
    }

    // ── ViewModel flows ──────────────────────────────────────────────────────

    @Test
    fun `parseMenu merges the draft and prefills a blank name`() = runTest {
        val repo = EditFakeRestaurantRepository()
        repo.parseResponse = parse(parsedComponent("Protein", "Chicken", official = true))
        val vm = RestaurantEditViewModel(repo, EditFakeFoodRepository())
        vm.setMenuUrl("https://x.example/menu")
        vm.parseMenu()
        advanceUntilIdle()
        assertEquals("Salsa Grille", vm.name.value)
        assertEquals(1, vm.components.value.size)
        assertTrue(vm.parseState.value is UiState.Success)
    }

    @Test
    fun `parseMenu with no url errors without a server call`() = runTest {
        val vm = RestaurantEditViewModel(EditFakeRestaurantRepository(), EditFakeFoodRepository())
        vm.parseMenu()
        assertTrue(vm.parseState.value is UiState.Error)
    }

    @Test
    fun `parseText sends pasted text (not a url) and merges the draft`() = runTest {
        val repo = EditFakeRestaurantRepository()
        repo.parseResponse = parse(parsedComponent("Protein", "Chicken", official = true))
        val vm = RestaurantEditViewModel(repo, EditFakeFoodRepository())
        vm.setMenuText("Chicken 180 cal 32g protein ...")
        vm.parseText()
        advanceUntilIdle()
        assertEquals("Chicken 180 cal 32g protein ...", repo.parsedText)
        assertNull(repo.parsedUrl)
        assertEquals(1, vm.components.value.size)
        assertTrue(vm.parseState.value is UiState.Success)
    }

    @Test
    fun `parseText with no text errors without a server call`() = runTest {
        val repo = EditFakeRestaurantRepository()
        val vm = RestaurantEditViewModel(repo, EditFakeFoodRepository())
        vm.parseText()
        assertTrue(vm.parseState.value is UiState.Error)
        assertNull(repo.parsedText)
    }

    @Test
    fun `save builds the create payload with drafts and shared flag`() = runTest {
        val repo = EditFakeRestaurantRepository()
        repo.parseResponse = parse(
            parsedComponent("Protein", "Chicken", official = true),
            parsedComponent("Rice", "Cilantro Lime Rice"),
        )
        val vm = RestaurantEditViewModel(repo, EditFakeFoodRepository())
        vm.setName("Salsa Grille")
        vm.setMenuUrl("https://x.example/menu")
        vm.setShared(false)
        vm.parseMenu()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()

        assertEquals(false, repo.createdShared)
        val components = repo.createdComponents!!
        assertEquals(2, components.size)
        assertEquals(180.0, components[0].macros!!.kcal, 0.001) // official carried into the save
        assertEquals("food_x", components[1].foodId) // estimate saved as a food link
        assertTrue(vm.saveState.value is UiState.Success)
    }

    @Test
    fun `save without a name errors`() = runTest {
        val vm = RestaurantEditViewModel(EditFakeRestaurantRepository(), EditFakeFoodRepository())
        vm.save()
        assertTrue(vm.saveState.value is UiState.Error)
    }
}
