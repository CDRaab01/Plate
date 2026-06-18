package com.plate.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DiaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDay(day: CachedDayEntity)

    @Query("SELECT * FROM cached_day WHERE date = :date LIMIT 1")
    suspend fun getCachedDay(date: String): CachedDayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPending(entry: PendingQuickAddEntity)

    @Query("SELECT * FROM pending_quick_add WHERE date = :date ORDER BY createdAt")
    suspend fun pendingForDate(date: String): List<PendingQuickAddEntity>

    @Query("SELECT * FROM pending_quick_add ORDER BY createdAt")
    suspend fun allPending(): List<PendingQuickAddEntity>

    @Query("DELETE FROM pending_quick_add WHERE localId = :localId")
    suspend fun deletePending(localId: String)
}
