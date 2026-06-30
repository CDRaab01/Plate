package com.plate.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request body for POST /metrics/weight. `weight` is in `unit` (lb|kg); the server stores kg. */
@Serializable
data class BodyMetricCreate(
    val date: String,
    val weight: Double,
    val unit: String = "lb",
    val bodyfat: Double? = null,
)

/**
 * A weigh-in from the metrics API. `weightKg` is canonical (stable across the lb/kg toggle — use it
 * for charting); `weight` + `unit` are the display value in the user's preferred unit.
 */
@Serializable
data class BodyMetricOut(
    val id: String,
    @SerialName("user_id") val userId: String,
    val date: String,
    @SerialName("weight_kg") val weightKg: Double,
    val weight: Double,
    val unit: String,
    val bodyfat: Double? = null,
)

@Serializable
data class WeightTrendPoint(
    val date: String,
    val weight: Double,
)

/** GET /metrics/weight/trend — smoothed series + observed rate + on-pace status, in display units. */
@Serializable
data class WeightTrendOut(
    val points: List<WeightTrendPoint>,
    @SerialName("trend_weight") val trendWeight: Double? = null,
    @SerialName("observed_rate_per_week") val observedRatePerWeek: Double? = null,
    @SerialName("goal_rate_per_week") val goalRatePerWeek: Double,
    val unit: String,
    val status: String,
)
