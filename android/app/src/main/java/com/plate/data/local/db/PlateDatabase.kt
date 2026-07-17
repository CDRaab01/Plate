package com.plate.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local mirror for offline-tolerant diary use. Room is a cache of the server's source of truth —
 * except the offline write queues (`pending_quick_add`, `body_metrics` rows with `syncPending`),
 * which hold data the server hasn't seen yet.
 *
 * v1 → v2 adds `body_metrics` (weigh-ins); v2 → v3 adds the weigh-in offline-queue columns, the
 * `blob_cache` read-through table, and `cached_day.cachedAtMs`. Both are **additive** migrations
 * (see [DatabaseModule]), not destructive rebuilds — a rebuild would wipe the offline queues, the
 * tables holding un-synced local writes.
 */
@Database(
    entities = [
        CachedDayEntity::class,
        PendingQuickAddEntity::class,
        BodyMetricEntity::class,
        BlobCacheEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class PlateDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao
    abstract fun bodyMetricDao(): BodyMetricDao
    abstract fun blobCacheDao(): BlobCacheDao
}
