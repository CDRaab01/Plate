package com.plate.ui.restaurant

import android.content.Context
import com.plate.data.remote.ComponentMacrosIn
import com.plate.data.remote.MenuParseResponse
import com.plate.data.remote.RestaurantComponentIn
import com.plate.data.remote.RestaurantLogSelection
import com.plate.data.remote.RestaurantOut
import com.plate.data.repository.RestaurantRepository
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
import org.mockito.kotlin.mock

private fun restaurant(id: String, name: String, isOwner: Boolean = true) =
    RestaurantOut(id = id, name = name, isOwner = isOwner)

private class FakeRestaurantRepository(
    var restaurants: MutableList<RestaurantOut> = mutableListOf(restaurant("r1", "Salsa Grille")),
    private val failWith: Exception? = null,
) : RestaurantRepository {
    var deleted = 0
    var created: Pair<String, List<RestaurantComponentIn>>? = null
    var createdShared: Boolean? = null

    override suspend fun list(): List<RestaurantOut> {
        failWith?.let { throw it }
        return restaurants
    }

    override suspend fun get(id: String): RestaurantOut = restaurants.first { it.id == id }

    override suspend fun create(
        name: String,
        menuUrl: String?,
        shared: Boolean,
        components: List<RestaurantComponentIn>,
    ): RestaurantOut {
        created = name to components
        createdShared = shared
        return restaurant("new", name).also { restaurants.add(it) }
    }

    override suspend fun update(id: String, name: String?, menuUrl: String?, shared: Boolean?) =
        restaurants.first { it.id == id }

    override suspend fun replaceComponents(id: String, components: List<RestaurantComponentIn>) =
        restaurants.first { it.id == id }

    override suspend fun delete(id: String) {
        deleted++
        restaurants.removeAll { it.id == id }
    }

    override suspend fun parseMenu(url: String): MenuParseResponse =
        MenuParseResponse(menuUrl = url)

    override suspend fun log(
        id: String,
        date: String,
        meal: String,
        selections: List<RestaurantLogSelection>,
    ): Int = selections.size
}

@OptIn(ExperimentalCoroutinesApi::class)
class RestaurantListViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    // A bare mock Context has no assets — preset loading degrades to an empty list, which is
    // exactly the "broken bundle never takes the screen down" behavior under test.
    private val context: Context = mock()

    @Test
    fun `load surfaces the restaurants`() = runTest {
        val vm = RestaurantListViewModel(FakeRestaurantRepository(), context)
        advanceUntilIdle()
        val state = vm.restaurants.value
        assertTrue(state is UiState.Success && state.data.single().name == "Salsa Grille")
        assertEquals(emptyList<RestaurantPreset>(), vm.presets.value)
    }

    @Test
    fun `load failure surfaces an error`() = runTest {
        val vm = RestaurantListViewModel(
            FakeRestaurantRepository(failWith = RuntimeException("down")),
            context,
        )
        advanceUntilIdle()
        assertTrue(vm.restaurants.value is UiState.Error)
    }

    @Test
    fun `delete removes and reloads`() = runTest {
        val repo = FakeRestaurantRepository()
        val vm = RestaurantListViewModel(repo, context)
        advanceUntilIdle()
        vm.delete("r1")
        advanceUntilIdle()
        assertEquals(1, repo.deleted)
        val state = vm.restaurants.value
        assertTrue(state is UiState.Success && state.data.isEmpty())
    }

    @Test
    fun `importPreset creates a shared restaurant and confirms`() = runTest {
        val repo = FakeRestaurantRepository()
        val vm = RestaurantListViewModel(repo, context)
        advanceUntilIdle()
        vm.importPreset(
            RestaurantPreset(
                name = "Chipotle",
                components = listOf(
                    RestaurantComponentIn(
                        category = "Protein",
                        name = "Chicken",
                        macros = ComponentMacrosIn(
                            kcal = 180.0,
                            proteinG = 32.0,
                            carbsG = 0.0,
                            fatG = 7.0,
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals("Chipotle", repo.created!!.first)
        assertEquals(true, repo.createdShared)
        assertNotNull(vm.message.value)
    }
}
