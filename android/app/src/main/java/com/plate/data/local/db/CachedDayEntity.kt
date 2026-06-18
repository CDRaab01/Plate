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
)
