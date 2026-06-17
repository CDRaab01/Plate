package com.plate.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One day inside a range summary: that day's totals + its kcal target and training flag. */
@Serializable
data class DaySummary(
    val date: String,
    val totals: TotalsOut,
    @SerialName("target_kcal") val targetKcal: Double,
    val trained: Boolean = false,
)

/** A weekly (or arbitrary-range) summary: per-day totals plus period total and daily averages. */
@Serializable
data class RangeSummary(
    val start: String,
    val end: String,
    val days: List<DaySummary> = emptyList(),
    val total: TotalsOut,
    val averages: TotalsOut,
)

/** POST /log/quick-add: raw macros logged directly, with no source food. */
@Serializable
data class QuickAddRequest(
    val date: String,
    val meal: String,
    val name: String? = null,
    val kcal: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
)
