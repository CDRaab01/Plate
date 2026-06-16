package com.plate.ui.photo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.FoodCreateRequest
import com.plate.data.repository.FoodRepository
import com.plate.data.repository.LogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estimates below this confidence are flagged so the user double-checks before logging. */
const val LOW_CONFIDENCE = 0.4

/**
 * One editable estimate from the photo. The macros are for the whole portion (`grams`); the user can
 * tweak any field before logging. [logged] flips once it's been added to the diary so the card can
 * show as done and not be logged twice.
 */
data class PhotoDraft(
    val id: Int,
    val name: String,
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val confidence: Double,
    val logging: Boolean = false,
    val logged: Boolean = false,
) {
    val lowConfidence: Boolean get() = confidence <= LOW_CONFIDENCE
}

/** Screen state: nothing yet, analysing a photo, or a draft list (possibly empty) to confirm. */
data class PhotoUiState(
    val analyzing: Boolean = false,
    val analyzed: Boolean = false,
    val drafts: List<PhotoDraft> = emptyList(),
    val lowConfidence: Boolean = false,
    val note: String? = null,
    val error: String? = null,
)

/**
 * Drives photo logging (Phase 6, CLAUDE.md §3, §6). A picked/captured image is sent to the backend's
 * vision endpoint, which returns an editable draft of the foods it sees. The user edits and confirms
 * each item — only then is it logged (as a custom food + log entry). Nothing is ever auto-committed.
 */
@HiltViewModel
class PhotoLogViewModel @Inject constructor(
    private val foodRepository: FoodRepository,
    private val logRepository: LogRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PhotoUiState())
    val state: StateFlow<PhotoUiState> = _state

    fun analyze(image: ByteArray, mimeType: String) {
        if (_state.value.analyzing) return
        _state.value = PhotoUiState(analyzing = true)
        viewModelScope.launch {
            _state.value = try {
                val result = foodRepository.estimatePhoto(image, mimeType)
                PhotoUiState(
                    analyzed = true,
                    drafts = result.items.mapIndexed { i, item ->
                        PhotoDraft(
                            id = i,
                            name = item.name,
                            grams = item.estGrams,
                            kcal = item.kcal,
                            protein = item.proteinG,
                            carbs = item.carbsG,
                            fat = item.fatG,
                            confidence = item.confidence,
                        )
                    },
                    lowConfidence = result.lowConfidence,
                    note = result.note,
                )
            } catch (e: Exception) {
                PhotoUiState(error = e.message ?: "Couldn't analyze that photo")
            }
        }
    }

    /**
     * Log a confirmed (and possibly edited) draft into [meal] on [date]. Persists it as a one-serving
     * custom food so the entry keeps its name, then logs the portion. [onLogged] fires on success so
     * the shared diary can refresh.
     */
    fun logDraft(edited: PhotoDraft, meal: String, date: String, onLogged: () -> Unit) {
        val current = _state.value.drafts.firstOrNull { it.id == edited.id } ?: return
        if (current.logging || current.logged) return
        updateDraft(edited.id) { it.copy(logging = true) }
        viewModelScope.launch {
            try {
                val grams = edited.grams
                val food = foodRepository.createFood(
                    FoodCreateRequest(
                        name = edited.name.ifBlank { "Photo estimate" },
                        servingSize = grams.takeIf { it > 0 },
                        servingUnit = "g",
                        kcalPer100g = per100g(edited.kcal, grams),
                        proteinGPer100g = per100g(edited.protein, grams),
                        carbsGPer100g = per100g(edited.carbs, grams),
                        fatGPer100g = per100g(edited.fat, grams),
                    ),
                )
                logRepository.addEntry(food.id, date, meal, grams, "g")
                updateDraft(edited.id) { it.copy(logging = false, logged = true) }
                onLogged()
            } catch (e: Exception) {
                updateDraft(edited.id) { it.copy(logging = false) }
                _state.update { it.copy(error = e.message ?: "Couldn't log that food") }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Discard the current estimate so a new photo can be analysed. */
    fun reset() {
        _state.value = PhotoUiState()
    }

    private fun updateDraft(id: Int, transform: (PhotoDraft) -> PhotoDraft) {
        _state.update { s ->
            s.copy(drafts = s.drafts.map { if (it.id == id) transform(it) else it })
        }
    }

    companion object {
        /**
         * Convert a per-portion macro to a per-100 g value so the server (which stores per 100 g and
         * scales by grams) reproduces the exact logged amount. A zero/blank portion can't be scaled,
         * so it contributes nothing rather than dividing by zero.
         */
        internal fun per100g(value: Double, grams: Double): Double =
            if (grams > 0.0) value * 100.0 / grams else 0.0
    }
}
