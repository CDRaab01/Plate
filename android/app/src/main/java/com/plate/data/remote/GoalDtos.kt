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
