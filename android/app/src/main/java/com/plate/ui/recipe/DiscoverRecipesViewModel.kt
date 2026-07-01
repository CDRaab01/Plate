package com.plate.ui.recipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.DiscoveredRecipe
import com.plate.data.remote.RecipeOut
import com.plate.data.repository.LogRepository
import com.plate.data.repository.RecipeRepository
import com.plate.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.time.LocalDate
import javax.inject.Inject

/**
 * Discover external recipes, import one as a saved recipe, then optionally log all its parts to a
 * meal now (or leave it saved for later). Import reuses the recipe log flow — a discovered recipe
 * becomes an ordinary Plate recipe.
 */
@HiltViewModel
class DiscoverRecipesViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val logRepository: LogRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<UiState<List<DiscoveredRecipe>>>(UiState.Idle)
    val results: StateFlow<UiState<List<DiscoveredRecipe>>> = _results

    /** The just-imported recipe awaiting a "log now / save for later" choice (drives a dialog). */
    private val _pending = MutableStateFlow<RecipeOut?>(null)
    val pending: StateFlow<RecipeOut?> = _pending

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun setQuery(q: String) {
        _query.value = q
    }

    fun search() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _results.value = UiState.Loading
            _results.value = try {
                UiState.Success(recipeRepository.discover(q))
            } catch (e: Exception) {
                UiState.Error(discoverError(e))
            }
        }
    }

    /** Import a discovered recipe; on success surface the log-now/save-later choice. */
    fun import(sourceId: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                _pending.value = recipeRepository.importRecipe(sourceId)
            } catch (e: Exception) {
                _message.value = discoverError(e, fallback = "Couldn't import that recipe")
            } finally {
                _busy.value = false
            }
        }
    }

    /** Log every ingredient of the pending recipe to today's [meal]. */
    fun logPendingTo(meal: String) {
        val recipe = _pending.value ?: return
        viewModelScope.launch {
            _busy.value = true
            try {
                logRepository.logRecipe(recipe.id, LocalDate.now().toString(), meal)
                _message.value = "Added ${recipe.name} to $meal"
                _pending.value = null
            } catch (e: Exception) {
                _message.value = e.message ?: "Saved the recipe, but couldn't log it"
            } finally {
                _busy.value = false
            }
        }
    }

    /** Keep the imported recipe saved without logging it now. */
    fun keepPendingSaved() {
        _message.value = _pending.value?.let { "Saved \"${it.name}\" to your recipes" }
        _pending.value = null
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun discoverError(e: Exception, fallback: String = "Search failed"): String =
        if (e is HttpException && e.code() == 503) {
            "Recipe discovery isn't set up on the server yet."
        } else {
            e.message ?: fallback
        }
}
