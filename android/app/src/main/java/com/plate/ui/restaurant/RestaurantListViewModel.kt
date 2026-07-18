package com.plate.ui.restaurant

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.RestaurantOut
import com.plate.data.repository.RestaurantRepository
import com.plate.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restaurants list: the caller's own + other accounts' shared templates, plus one-tap import of
 * the bundled chain presets. A transient [message] drives a confirmation snackbar.
 */
@HiltViewModel
class RestaurantListViewModel @Inject constructor(
    private val repository: RestaurantRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _restaurants = MutableStateFlow<UiState<List<RestaurantOut>>>(UiState.Loading)
    val restaurants: StateFlow<UiState<List<RestaurantOut>>> = _restaurants

    private val _presets = MutableStateFlow<List<RestaurantPreset>>(emptyList())
    val presets: StateFlow<List<RestaurantPreset>> = _presets

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        load()
        loadPresets()
    }

    fun load() {
        viewModelScope.launch {
            _restaurants.value = UiState.Loading
            _restaurants.value = try {
                UiState.Success(repository.list())
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load your restaurants")
            }
        }
    }

    private fun loadPresets() {
        viewModelScope.launch {
            _presets.value = try {
                PresetParser.parse(
                    context.assets.open("restaurant_presets.json").bufferedReader().readText(),
                )
            } catch (e: Exception) {
                emptyList() // a broken bundle should never take the screen down
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repository.delete(id)
                load()
            } catch (e: Exception) {
                _message.value = e.message ?: "Couldn't delete that restaurant"
            }
        }
    }

    /** Import a bundled preset as an ordinary (shared) restaurant. */
    fun importPreset(preset: RestaurantPreset) {
        viewModelScope.launch {
            try {
                repository.create(
                    name = preset.name,
                    menuUrl = preset.menuUrl,
                    shared = true,
                    components = preset.components,
                )
                _message.value = "Added ${preset.name} (${preset.components.size} components)"
                load()
            } catch (e: Exception) {
                _message.value = e.message ?: "Couldn't add ${preset.name}"
            }
        }
    }

    /** Surface a one-shot confirmation (e.g. from the log sheet) via the shared snackbar. */
    fun notify(text: String) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = null
    }
}
