package com.plate.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The last server-fetched diary for a date, stored as the serialized `DailyLog` JSON. Lets the
 * diary render offline (read-through cache): on a successful fetch we overwrite this row; when the
 * network is down we fall back to it. The server stays the source of truth — this is only a mirror.
 */
@Entity(tableName = "cached_day")
data class CachedDayEntity(
    @PrimaryKey val date: String,
    val json: String,
    /**
     * Epoch millis when this day was fetched — the "as of" time shown by the diary's stale banner
     * when the row is served offline. 0 on rows cached before the v3 schema (age unknown).
     */
    val cachedAtMs: Long = 0,
)
