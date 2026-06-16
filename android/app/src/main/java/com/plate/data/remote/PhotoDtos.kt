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
)

@Serializable
data class PhotoEstimateResponse(
    val items: List<PhotoEstimateItem>,
    @SerialName("low_confidence") val lowConfidence: Boolean,
    val note: String? = null,
)
