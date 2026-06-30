package com.plate.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BodyMetricDao {
    /** All weigh-ins, oldest first — the Home trend observes this. */
    @Query("SELECT * FROM body_metrics ORDER BY date ASC")
    fun observeAll(): Flow<List<BodyMetricEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(metrics: List<BodyMetricEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metric: BodyMetricEntity)
}
