package com.plate.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A cached weigh-in — **offline-capable, write-through** (the Spotter MetricRepository pattern):
 * a weigh-in lands here immediately (id = a local UUID, [syncPending] = true), is pushed
 * best-effort, and a successful push replaces the local row with the acknowledged server row
 * (id = [serverId], [syncPending] = false). Weight is stored canonical in **kg** — conversion to
 * the display unit happens in the UI, and the offline queue stores kg too, so flipping the lb/kg
 * toggle can never corrupt a queued entry.
 */
@Entity(tableName = "body_metrics")
data class BodyMetricEntity(
    @PrimaryKey val id: String,
    val date: String,
    val weightKg: Double,
    val bodyfat: Double? = null,
    /** The server's id once acknowledged; null while the row is an offline-queued weigh-in. */
    val serverId: String? = null,
    /** True while the row still needs to be POSTed (offline write-through queue). */
    val syncPending: Boolean = false,
)
