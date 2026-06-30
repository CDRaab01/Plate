package com.plate.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A cached weigh-in (server is the source of truth; this mirror lets the Home trend render offline).
 * Weight is stored canonical in **kg** — display conversion to lb happens in the UI per the user's
 * unit preference, so flipping units never rewrites cached rows.
 */
@Entity(tableName = "body_metrics")
data class BodyMetricEntity(
    @PrimaryKey val id: String,
    val date: String,
    val weightKg: Double,
    val bodyfat: Double? = null,
)
