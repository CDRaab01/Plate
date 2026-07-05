package com.plate.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request body for PUT /goals — mirrors GoalUpsert on the backend. */
@Serializable
data class GoalUpsertRequest(
    @SerialName("goal_type") val goalType: String,
    @SerialName("weight_kg") val weightKg: Double,
    @SerialName("height_cm") val heightCm: Double,
    val age: Int,
    val sex: String,
    @SerialName("activity_level") val activityLevel: String,
    @SerialName("rate_kg_per_week") val rateKgPerWeek: Double = 0.0,
)

/** The active goal returned by GET /goals and PUT /goals. */
@Serializable
data class GoalOut(
    val id: String,
    @SerialName("goal_type") val goalType: String,
    @SerialName("weight_kg") val weightKg: Double,
    @SerialName("height_cm") val heightCm: Double,
    val age: Int,
    val sex: String,
    @SerialName("activity_level") val activityLevel: String,
    @SerialName("rate_kg_per_week") val rateKgPerWeek: Double,
    @SerialName("created_at") val createdAt: String,
)

/** Computed targets for a date from GET /goals/targets. */
@Serializable
data class TargetsOut(
    val date: String,
    val kcal: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
)

/**
 * Adaptive-TDEE state from GET /goals/adaptive (ROADMAP2 T3 #1). All kcal.
 *
 * [status] is `insufficient_data`, `learning`, or `active`; [observedMaintenance] is non-null only
 * when active. The client shows [nLoggedDays]/[minLoggedDays] as progress toward unlocking it.
 */
@Serializable
data class AdaptiveTdeeOut(
    val date: String,
    val status: String,
    @SerialName("formula_tdee") val formulaTdee: Double,
    @SerialName("corrected_tdee") val correctedTdee: Double,
    @SerialName("observed_maintenance") val observedMaintenance: Double? = null,
    @SerialName("adjustment_kcal") val adjustmentKcal: Double,
    val confidence: Double,
    @SerialName("n_logged_days") val nLoggedDays: Int,
    @SerialName("window_days") val windowDays: Int,
    @SerialName("min_logged_days") val minLoggedDays: Int,
)
