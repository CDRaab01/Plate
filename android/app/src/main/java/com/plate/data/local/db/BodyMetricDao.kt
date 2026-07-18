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

    /** Offline-queued weigh-ins still awaiting a successful POST, oldest first. */
    @Query("SELECT * FROM body_metrics WHERE syncPending = 1 ORDER BY date ASC")
    suspend fun getUnsynced(): List<BodyMetricEntity>

    /** Removes a row by id — used to promote a local offline row to its acknowledged server row. */
    @Query("DELETE FROM body_metrics WHERE id = :id")
    suspend fun deleteById(id: String)
}
