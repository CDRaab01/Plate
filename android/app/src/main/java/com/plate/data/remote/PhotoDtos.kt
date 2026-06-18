package com.plate.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Photo-logging DTOs (Phase 6, CLAUDE.md §6). The server's vision model returns an **editable
 * draft** of the foods it sees; these numbers are estimates the user confirms before anything is
 * logged. Macros are for the whole estimated portion (`est_grams`), not per 100 g.
 */
@Serializable
data class PhotoEstimateItem(
    val name: String,
    @SerialName("est_grams") val estGrams: Double,
    val kcal: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    val confidence: Double,
    // When the identified food matched a canonical row in the backend's DB, the macros above are the
    // looked-up values and these point at that food (so we can log it directly instead of minting a
    // custom food). `source` is the matched row's origin, or "estimate" for the model's own guess.
    @SerialName("matched_food_id") val matchedFoodId: String? = null,
    @SerialName("matched_name") val matchedName: String? = null,
    val source: String = "estimate",
)

@Serializable
data class PhotoEstimateResponse(
    val items: List<PhotoEstimateItem>,
    @SerialName("low_confidence") val lowConfidence: Boolean,
    val note: String? = null,
)
