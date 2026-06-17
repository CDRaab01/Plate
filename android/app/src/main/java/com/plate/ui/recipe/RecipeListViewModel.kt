package com.plate.ui.recipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.RecipeOut
import com.plate.data.repository.LogRepository
import com.plate.data.repository.RecipeRepository
import com.plate.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Saved meals / recipes (Phase 8): list the user's recipes, delete one, and log a recipe into
 * today's diary at a chosen meal. A transient [message] drives a confirmation snackbar.
 */
@HiltViewModel
class RecipeListViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val logRepository: LogRepository,
) : ViewModel() {

    private val _recipes = MutableStateFlow<UiState<List<RecipeOut>>>(UiState.Loading)
    val recipes: StateFlow<UiState<List<RecipeOut>>> = _recipes

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _recipes.value = UiState.Loading
            _recipes.value = try {
                UiState.Success(recipeRepository.list())
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load your recipes")
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                recipeRepository.delete(id)
                load()
            } catch (e: Exception) {
                _message.value = e.message ?: "Couldn't delete that recipe"
            }
        }
    }

    /** Log every item of [recipe] into today's [meal]. */
    fun logToday(recipe: RecipeOut, meal: String) {
        viewModelScope.launch {
            try {
                val entries = logRepository.logRecipe(recipe.id, LocalDate.now().toString(), meal)
                _message.value = "Logged ${recipe.name} (${entries.size} items) to $meal"
            } catch (e: Exception) {
                _message.value = e.message ?: "Couldn't log that recipe"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
