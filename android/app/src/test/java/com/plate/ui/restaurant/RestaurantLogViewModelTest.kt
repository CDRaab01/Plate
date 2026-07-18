package com.plate.ui.restaurant

import com.plate.data.remote.MenuParseResponse
import com.plate.data.remote.RestaurantComponentIn
import com.plate.data.remote.RestaurantComponentOut
import com.plate.data.remote.RestaurantLogSelection
import com.plate.data.remote.RestaurantOut
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

private fun component(
    id: String,
    category: String = "Rice",
    quantity: Double = 100.0,
    kcal: Double? = 89.0,
    defaultChecked: Boolean = false,
) = RestaurantComponentOut(
    id = id,
    category = category,
    name = "Component $id",
    foodId = if (kcal == null) null else "food_$id",
    foodName = if (kcal == null) null else "Food $id",
    quantity = quantity,
    unit = "g",
    order = 0,
    defaultChecked = defaultChecked,
    kcal = kcal,
    proteinG = kcal?.let { it / 10 },
    carbsG = kcal?.let { it / 5 },
    fatG = kcal?.let { it / 20 },
)

private fun bowlOf(vararg components: RestaurantComponentOut) = RestaurantOut(
    id = "r1",
    name = "Salsa Grille",
    components = components.toList(),
)

private class LogFakeRestaurantRepository(
    var restaurant: RestaurantOut = bowlOf(),
    private val failWith: Exception? = null,
) : RestaurantRepository {
    var loggedSelections: List<RestaurantLogSelection>? = null
    var loggedMeal: String? = null
    var created: com.plate.data.remote.RestaurantCreate? = null

    override suspend fun list(): List<RestaurantOut> {
        failWith?.let { throw it }
        return listOf(restaurant)
    }

    override suspend fun get(id: String): RestaurantOut {
        failWith?.let { throw it }
        return restaurant
    }

    override suspend fun create(
        name: String,
        menuUrl: String?,
        shared: Boolean,
        components: List<RestaurantComponentIn>,
    ): RestaurantOut {
        created = com.plate.data.remote.RestaurantCreate(name, menuUrl, null, shared, components)
        return restaurant
    }

    override suspend fun update(id: String, name: String?, menuUrl: String?, shared: Boolean?) =
        restaurant

    override suspend fun replaceComponents(id: String, components: List<RestaurantComponentIn>) =
        restaurant

    override suspend fun delete(id: String) {}

    override suspend fun parseMenu(url: String): MenuParseResponse =
        MenuParseResponse(menuUrl = url)

    override suspend fun log(
        id: String,
        date: String,
        meal: String,
        selections: List<RestaurantLogSelection>,
    ): Int {
        failWith?.let { throw it }
        loggedSelections = selections
        loggedMeal = meal
        return selections.size
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RestaurantLogViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    // ── Pure helpers ─────────────────────────────────────────────────────────

    @Test
    fun `running totals sum only ticked components`() {
        val components = listOf(
            component("a", kcal = 100.0),
            component("b", kcal = 50.0),
            component("c", kcal = 25.0),
        )
        val totals = runningTotals(components, setOf("a", "c"), emptyMap())
        assertEquals(125.0, totals.kcal, 0.001)
    }

    @Test
    fun `running totals scale overridden quantities from the default portion`() {
        val components = listOf(component("a", quantity = 100.0, kcal = 100.0))
        val totals = runningTotals(components, setOf("a"), mapOf("a" to 250.0))
        assertEquals(250.0, totals.kcal, 0.001)
        assertEquals(25.0, totals.proteinG, 0.001)
    }

    @Test
    fun `unlinked components contribute nothing to totals`() {
        val components = listOf(component("a", kcal = null), component("b", kcal = 80.0))
        val totals = runningTotals(components, setOf("a", "b"), emptyMap())
        assertEquals(80.0, totals.kcal, 0.001)
    }

    @Test
    fun `buildSelections carries an override only when it differs from the default`() {
        val components = listOf(
            component("a", quantity = 100.0),
            component("b", quantity = 50.0),
            component("c", quantity = 30.0),
        )
        val selections = buildSelections(
            components,
            setOf("a", "b"),
            mapOf("a" to 150.0, "b" to 50.0), // b's override equals the default
        )
        assertEquals(listOf("a", "b"), selections.map { it.componentId })
        assertEquals(150.0, selections[0].quantity!!, 0.001)
        assertNull(selections[1].quantity)
    }

    // ── ViewModel flows ──────────────────────────────────────────────────────

    @Test
    fun `load pre-ticks default_checked components`() = runTest {
        val repo = LogFakeRestaurantRepository(
            bowlOf(component("a", defaultChecked = true), component("b")),
        )
        val vm = RestaurantLogViewModel(repo)
        vm.load("r1")
        advanceUntilIdle()
        assertEquals(setOf("a"), vm.checked.value)
        assertTrue(vm.restaurant.value is UiState.Success)
    }

    @Test
    fun `toggle adds and removes a component`() = runTest {
        val vm = RestaurantLogViewModel(LogFakeRestaurantRepository(bowlOf(component("a"))))
        vm.load("r1")
        advanceUntilIdle()
        vm.toggle("a")
        assertEquals(setOf("a"), vm.checked.value)
        vm.toggle("a")
        assertEquals(emptySet<String>(), vm.checked.value)
    }

    @Test
    fun `log sends ticked selections and reports the created count`() = runTest {
        val repo = LogFakeRestaurantRepository(
            bowlOf(component("a", defaultChecked = true), component("b")),
        )
        val vm = RestaurantLogViewModel(repo)
        vm.load("r1")
        advanceUntilIdle()
        vm.toggle("b")
        vm.setQuantity("b", 42.0)
        vm.log("dinner", date = "2026-07-18")
        advanceUntilIdle()
        assertEquals(UiState.Success(2), vm.logState.value)
        assertEquals("dinner", repo.loggedMeal)
        assertEquals(42.0, repo.loggedSelections!!.first { it.componentId == "b" }.quantity!!, 0.001)
    }

    @Test
    fun `log with nothing ticked errors without calling the server`() = runTest {
        val repo = LogFakeRestaurantRepository(bowlOf(component("a")))
        val vm = RestaurantLogViewModel(repo)
        vm.load("r1")
        advanceUntilIdle()
        vm.log("lunch")
        advanceUntilIdle()
        assertTrue(vm.logState.value is UiState.Error)
        assertNull(repo.loggedSelections)
    }

    @Test
    fun `load failure surfaces an error state`() = runTest {
        val vm = RestaurantLogViewModel(
            LogFakeRestaurantRepository(failWith = RuntimeException("boom")),
        )
        vm.load("r1")
        advanceUntilIdle()
        assertTrue(vm.restaurant.value is UiState.Error)
    }
}
