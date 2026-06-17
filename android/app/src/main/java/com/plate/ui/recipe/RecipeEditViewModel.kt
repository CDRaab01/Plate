package com.plate.ui.recipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.FoodOut
import com.plate.data.remote.RecipeItemIn
import com.plate.data.repository.FoodRepository
import com.plate.data.repository.RecipeRepository
import com.plate.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One pending item in the recipe being edited: a food plus the amount it contributes. */
data class DraftItem(
    val food: FoodOut,
    val quantity: Double = 100.0,
    val unit: String = "g",
)

/**
 * Create or edit a saved recipe (Phase 8): name/description + a list of foods, each found via the
 * shared food search. Save creates a new recipe or replaces an existing recipe's items.
 */
@HiltViewModel
class RecipeEditViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val foodRepository: FoodRepository,
) : ViewModel() {

    private var editingId: String? = null

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description

    private val _items = MutableStateFlow<List<DraftItem>>(emptyList())
    val items: StateFlow<List<DraftItem>> = _items

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<UiState<List<FoodOut>>>(UiState.Idle)
    val results: StateFlow<UiState<List<FoodOut>>> = _results

    private val _saveState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val saveState: StateFlow<UiState<Unit>> = _saveState

    /** Populate the editor from an existing recipe (skip for a new recipe). */
    fun loadExisting(id: String) {
        viewModelScope.launch {
            try {
                val recipe = recipeRepository.get(id)
                editingId = recipe.id
                _name.value = recipe.name
                _description.value = recipe.description.orEmpty()
                _items.value = recipe.items.mapNotNull { item ->
                    val fid = item.foodId ?: return@mapNotNull null
                    DraftItem(
                        food = FoodOut(
                            id = fid,
                            source = "user",
                            name = item.foodName ?: "Food",
                            kcalPer100g = 0.0,
                            proteinGPer100g = 0.0,
                            carbsGPer100g = 0.0,
                            fatGPer100g = 0.0,
                        ),
                        quantity = item.quantity,
                        unit = item.unit,
                    )
                }
            } catch (e: Exception) {
                _saveState.value = UiState.Error(e.message ?: "Couldn't load that recipe")
            }
        }
    }

    fun setName(value: String) { _name.value = value }
    fun setDescription(value: String) { _description.value = value }
    fun setQuery(value: String) { _query.value = value }

    fun search() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _results.value = UiState.Loading
            _results.value = try {
                UiState.Success(foodRepository.search(q))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Search failed")
            }
        }
    }

    fun addItem(food: FoodOut) {
        _items.value = _items.value + DraftItem(food)
    }

    fun removeItem(index: Int) {
        _items.value = _items.value.filterIndexed { i, _ -> i != index }
    }

    fun setQuantity(index: Int, quantity: Double) {
        _items.value = _items.value.mapIndexed { i, it ->
            if (i == index) it.copy(quantity = quantity) else it
        }
    }

    fun save() {
        val name = _name.value.trim()
        if (name.isEmpty()) {
            _saveState.value = UiState.Error("Give your recipe a name")
            return
        }
        val itemsIn = _items.value.map { RecipeItemIn(it.food.id, it.quantity, it.unit) }
        viewModelScope.launch {
            _saveState.value = UiState.Loading
            _saveState.value = try {
                val id = editingId
                if (id == null) {
                    recipeRepository.create(name, _description.value.ifBlank { null }, itemsIn)
                } else {
                    recipeRepository.rename(id, name, _description.value)
                    recipeRepository.replaceItems(id, itemsIn)
                }
                UiState.Success(Unit)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't save your recipe")
            }
        }
    }
}
