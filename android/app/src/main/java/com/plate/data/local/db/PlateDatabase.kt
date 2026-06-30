package com.plate.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local mirror for offline-tolerant diary use. Room is a cache of the server's source of truth.
 *
 * v1 → v2 adds `body_metrics` (weigh-ins) via an **additive** migration (see [DatabaseModule]),
 * not a destructive rebuild — a rebuild would wipe the offline `pending_quick_add` queue, which is
 * the one table holding un-synced local writes.
 */
@Database(
    entities = [CachedDayEntity::class, PendingQuickAddEntity::class, BodyMetricEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class PlateDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao
    abstract fun bodyMetricDao(): BodyMetricDao
}
