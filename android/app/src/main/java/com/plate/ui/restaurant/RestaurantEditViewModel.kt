package com.plate.ui.restaurant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.ComponentMacrosIn
import com.plate.data.remote.FoodOut
import com.plate.data.remote.MenuParseResponse
import com.plate.data.remote.RestaurantComponentIn
import com.plate.data.repository.FoodRepository
import com.plate.data.repository.RestaurantRepository
import com.plate.util.UiState
import com.plate.util.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One pending checkbox row in the restaurant being edited. Exactly one of [foodId]/[macros] is
 * set for a linked row ([foodName] captions the estimate source); both null = unlinked (saved,
 * but skipped when logging until linked).
 */
data class ComponentDraft(
    val category: String,
    val name: String,
    val foodId: String? = null,
    val foodName: String? = null,
    val macros: ComponentMacrosIn? = null,
    val quantity: Double = 1.0,
    val unit: String = "serving",
    val defaultChecked: Boolean = false,
    val kcal: Double? = null,
) {
    fun toIn() = RestaurantComponentIn(
        category = category,
        name = name,
        foodId = foodId,
        macros = if (foodId == null) macros else null,
        quantity = quantity,
        unit = unit,
        defaultChecked = defaultChecked,
    )
}

/** Group drafts by category preserving first-appearance order. Pure — unit-tested. */
fun groupByCategory(drafts: List<ComponentDraft>): Map<String, List<ComponentDraft>> {
    val grouped = LinkedHashMap<String, MutableList<ComponentDraft>>()
    drafts.forEach { grouped.getOrPut(it.category) { mutableListOf() }.add(it) }
    return grouped
}

/**
 * Merge a parse result into the in-memory draft: parsed components are appended, but rows the
 * user already has (same category + name, case-insensitive) are kept untouched — a re-parse
 * never clobbers manual edits. Pure — unit-tested.
 */
fun mergeParsedComponents(
    existing: List<ComponentDraft>,
    parsed: MenuParseResponse,
): List<ComponentDraft> {
    val seen = existing.map { "${it.category.lowercase()}|${it.name.lowercase()}" }.toMutableSet()
    val added = parsed.components.mapNotNull { component ->
        val key = "${component.category.lowercase()}|${component.name.lowercase()}"
        if (!seen.add(key)) return@mapNotNull null
        ComponentDraft(
            category = component.category,
            name = component.name,
            foodId = component.foodId,
            foodName = component.foodName,
            macros = component.macros,
            quantity = component.quantity,
            unit = component.unit,
            kcal = component.kcal,
        )
    }
    return existing + added
}

/**
 * Create or edit a restaurant template: name/link/shared + categorized component drafts, built by
 * hand (embedded food search, the recipe-editor pattern) or merged in from a parsed menu link.
 * Nothing touches the server until Save — the parse draft lives only in this VM (never
 * auto-committed, CLAUDE.md §3).
 */
@HiltViewModel
class RestaurantEditViewModel @Inject constructor(
    private val repository: RestaurantRepository,
    private val foodRepository: FoodRepository,
) : ViewModel() {

    private var editingId: String? = null

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    private val _menuUrl = MutableStateFlow("")
    val menuUrl: StateFlow<String> = _menuUrl

    private val _menuText = MutableStateFlow("")
    val menuText: StateFlow<String> = _menuText

    private val _shared = MutableStateFlow(true)
    val shared: StateFlow<Boolean> = _shared

    private val _components = MutableStateFlow<List<ComponentDraft>>(emptyList())
    val components: StateFlow<List<ComponentDraft>> = _components

    private val _newCategory = MutableStateFlow("")
    val newCategory: StateFlow<String> = _newCategory

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<UiState<List<FoodOut>>>(UiState.Idle)
    val results: StateFlow<UiState<List<FoodOut>>> = _results

    private val _parseState = MutableStateFlow<UiState<String?>>(UiState.Idle)
    val parseState: StateFlow<UiState<String?>> = _parseState

    private val _saveState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val saveState: StateFlow<UiState<Unit>> = _saveState

    /** Populate the editor from an existing restaurant (skip for a new one). */
    fun loadExisting(id: String) {
        viewModelScope.launch {
            try {
                val restaurant = repository.get(id)
                editingId = restaurant.id
                _name.value = restaurant.name
                _menuUrl.value = restaurant.menuUrl.orEmpty()
                _shared.value = restaurant.shared
                _components.value = restaurant.components.map { component ->
                    ComponentDraft(
                        category = component.category,
                        name = component.name,
                        foodId = component.foodId,
                        foodName = component.foodName,
                        quantity = component.quantity,
                        unit = component.unit,
                        defaultChecked = component.defaultChecked,
                        kcal = component.kcal,
                    )
                }
            } catch (e: Exception) {
                _saveState.value = UiState.Error(e.userMessage("Couldn't load that restaurant"))
            }
        }
    }

    fun setName(value: String) { _name.value = value }
    fun setMenuUrl(value: String) { _menuUrl.value = value }
    fun setMenuText(value: String) { _menuText.value = value }
    fun setShared(value: Boolean) { _shared.value = value }
    fun setNewCategory(value: String) { _newCategory.value = value }
    fun setQuery(value: String) { _query.value = value }

    /** Fetch + parse the menu link server-side, then merge the draft in (manual edits win). */
    fun parseMenu() {
        val url = _menuUrl.value.trim()
        if (url.isEmpty()) {
            _parseState.value = UiState.Error("Paste the menu's web address first")
            return
        }
        runParse { repository.parseMenu(url = url) }
    }

    /** Parse pasted menu/nutrition text (no fetch) — the robust path when a URL won't load. */
    fun parseText() {
        val text = _menuText.value.trim()
        if (text.isEmpty()) {
            _parseState.value = UiState.Error("Paste the menu or nutrition text first")
            return
        }
        runParse { repository.parseMenu(text = text) }
    }

    /** Shared parse→merge handling for both the URL and paste-text paths. */
    private fun runParse(call: suspend () -> com.plate.data.remote.MenuParseResponse) {
        viewModelScope.launch {
            _parseState.value = UiState.Loading
            try {
                val parsed = call()
                if (_name.value.isBlank() && parsed.restaurantName != null) {
                    _name.value = parsed.restaurantName
                }
                _components.value = mergeParsedComponents(_components.value, parsed)
                _parseState.value = UiState.Success(
                    parsed.note
                        ?: "Found ${parsed.components.size} components — review before saving",
                )
            } catch (e: Exception) {
                _parseState.value = UiState.Error(
                    e.userMessage("Couldn't read that menu — add components manually"),
                )
            }
        }
    }

    fun search() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _results.value = UiState.Loading
            _results.value = try {
                UiState.Success(foodRepository.search(q))
            } catch (e: Exception) {
                UiState.Error(e.userMessage("Search failed"))
            }
        }
    }

    /** Add a searched food as a component under the currently-entered category. */
    fun addComponent(food: FoodOut) {
        val category = _newCategory.value.trim().ifEmpty { "Menu" }
        _components.value = _components.value + ComponentDraft(
            category = category,
            name = food.name,
            foodId = food.id,
            foodName = food.name,
            quantity = 100.0,
            unit = "g",
            kcal = food.kcalPer100g,
        )
    }

    fun removeComponent(index: Int) {
        _components.value = _components.value.filterIndexed { i, _ -> i != index }
    }

    fun setQuantity(index: Int, quantity: Double) {
        _components.value = _components.value.mapIndexed { i, draft ->
            if (i == index) draft.copy(quantity = quantity) else draft
        }
    }

    fun setDefaultChecked(index: Int, value: Boolean) {
        _components.value = _components.value.mapIndexed { i, draft ->
            if (i == index) draft.copy(defaultChecked = value) else draft
        }
    }

    fun save() {
        val name = _name.value.trim()
        if (name.isEmpty()) {
            _saveState.value = UiState.Error("Give the restaurant a name")
            return
        }
        val componentsIn = _components.value.map { it.toIn() }
        val menuUrl = _menuUrl.value.trim().ifBlank { null }
        viewModelScope.launch {
            _saveState.value = UiState.Loading
            _saveState.value = try {
                val id = editingId
                if (id == null) {
                    repository.create(name, menuUrl, _shared.value, componentsIn)
                } else {
                    repository.update(id, name, menuUrl, _shared.value)
                    repository.replaceComponents(id, componentsIn)
                }
                UiState.Success(Unit)
            } catch (e: Exception) {
                UiState.Error(e.userMessage("Couldn't save the restaurant"))
            }
        }
    }
}
