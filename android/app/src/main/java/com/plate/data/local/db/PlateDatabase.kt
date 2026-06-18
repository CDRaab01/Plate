package com.plate.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local mirror for offline-tolerant diary use. Room is a cache of the server's source of truth, so
 * schema changes rebuild destructively (`fallbackToDestructiveMigration`, set where it's provided) —
 * matching Spotter's Room-as-mirror convention.
 */
@Database(
    entities = [CachedDayEntity::class, PendingQuickAddEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PlateDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao
}
