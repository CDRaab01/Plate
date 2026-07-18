package com.plate.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One cached read-endpoint response, stored as its serialized JSON under a stable key (e.g. "goal",
 * "weight_trend"). Backs the offline read-through caches: on a successful fetch the row is
 * overwritten (with the capture time), and when the server is unreachable the repository decodes it
 * into a [com.plate.data.repository.Stale] value so screens can render with an "as of" banner.
 * The server stays the source of truth — this is only a mirror, like [CachedDayEntity].
 */
@Entity(tableName = "blob_cache")
data class BlobCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    /** Epoch millis when this response was fetched — the "as of" time shown when served stale. */
    val cachedAtMs: Long,
)

@Dao
interface BlobCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: BlobCacheEntity)

    @Query("SELECT * FROM blob_cache WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): BlobCacheEntity?
}
