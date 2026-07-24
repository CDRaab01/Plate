package com.plate.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.FoodDetailOut
import com.plate.data.remote.FoodOut
import com.plate.data.remote.RecentFoodOut
import com.plate.data.repository.FoodRepository
import com.plate.util.AppPreferences
import com.plate.util.UiState
import com.plate.util.UnitSystem
import com.plate.util.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Search result scope, mirrored by the server's `filter` param. */
enum class SearchFilter(val wire: String, val label: String) {
    ALL("all", "All"),
    GENERIC("generic", "Generic"),
    BRANDED("branded", "Branded"),
    MINE("mine", "My foods"),
}

/**
 * Debounced food search. The query is debounced so we never issue a request per keystroke — the
 * server only reaches the external USDA/OFF APIs on a stale-query miss, so keeping calls coarse
 * matters (CLAUDE.md §5). Filter-chip taps aren't keystrokes and re-issue the search immediately.
 */
@HiltViewModel
class FoodSearchViewModel @Inject constructor(
    private val foodRepository: FoodRepository,
    appPreferences: AppPreferences,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<UiState<List<FoodOut>>>(UiState.Idle)
    val results: StateFlow<UiState<List<FoodOut>>> = _results

    /** Recently-logged foods, shown as one-tap re-log chips while the query is empty. */
    private val _recent = MutableStateFlow<List<RecentFoodOut>>(emptyList())
    val recent: StateFlow<List<RecentFoodOut>> = _recent

    /** Result-scope chips (All / Generic / Branded / My foods). */
    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter

    /** Detail (named portions) for the food whose add dialog is open. Loaded on food-tap. */
    private val _selectedDetail = MutableStateFlow<UiState<FoodDetailOut>>(UiState.Idle)
    val selectedDetail: StateFlow<UiState<FoodDetailOut>> = _selectedDetail

    /** The user's mass-unit preference — drives the picker's imperial/metric default. */
    val unitSystem: StateFlow<UnitSystem> = appPreferences.unitSystem
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UnitSystem.IMPERIAL)

    private var searchJob: Job? = null
    private var detailJob: Job? = null

    init {
        viewModelScope.launch {
            _recent.value = try {
                foodRepository.recentFoods()
            } catch (_: Exception) {
                emptyList() // recents are a convenience; never block search on them
            }
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
        scheduleSearch(debounce = true)
    }

    fun onFilterChange(value: SearchFilter) {
        if (_filter.value == value) return
        _filter.value = value
        // A chip tap is a deliberate action, not typing — re-run the current query immediately.
        scheduleSearch(debounce = false)
    }

    /** Fetch the tapped food's portions for the add dialog. Never fires per keystroke. */
    fun loadFoodDetail(foodId: String) {
        detailJob?.cancel()
        _selectedDetail.value = UiState.Loading
        detailJob = viewModelScope.launch {
            _selectedDetail.value = try {
                UiState.Success(foodRepository.getFood(foodId))
            } catch (e: Exception) {
                // Non-fatal: the dialog still works with serving/g/oz, just without portion chips.
                UiState.Error(e.userMessage("Couldn't load portions"))
            }
        }
    }

    private fun scheduleSearch(debounce: Boolean) {
        searchJob?.cancel()
        val value = _query.value
        if (value.isBlank()) {
            _results.value = UiState.Idle
            return
        }
        searchJob = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            _results.value = UiState.Loading
            _results.value = try {
                UiState.Success(foodRepository.search(value.trim(), _filter.value.wire))
            } catch (e: Exception) {
                UiState.Error(e.userMessage("Search failed"))
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 350L
    }
}
