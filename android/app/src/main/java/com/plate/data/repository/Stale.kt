package com.plate.data.repository

import com.plate.data.local.db.BlobCacheDao
import com.plate.data.local.db.BlobCacheEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A repository read that may have been served from a local cache while the server was unreachable.
 * [asOfMs] is the capture time of the cached response, or **null when the value is fresh** — the
 * screens use it to show a Pulse `StaleBanner` ("Offline — as of …") only over cached data.
 */
data class Stale<T>(val value: T, val asOfMs: Long?)

/**
 * The shared read-through cache over [BlobCacheDao] (offline reads, CLAUDE.md/Spotter precedent).
 * Semantics — deliberate and uniform across every cached read:
 *  - success → overwrite the cached blob, return fresh (`asOfMs = null`);
 *  - [IOException] (server unreachable) → decode the cached blob into a stale value, or rethrow
 *    when nothing is cached yet;
 *  - anything else — notably `retrofit2.HttpException`, a *reachable* server rejecting the request
 *    — always rethrows. Only unreachability degrades; rejections keep erroring.
 */
@Singleton
class BlobCache @Inject constructor(
    private val dao: BlobCacheDao,
    private val json: Json,
) {
    suspend fun <T> readThrough(
        key: String,
        serializer: KSerializer<T>,
        fetch: suspend () -> T,
    ): Stale<T> = try {
        val fresh = fetch()
        dao.upsert(BlobCacheEntity(key, json.encodeToString(serializer, fresh), System.currentTimeMillis()))
        Stale(fresh, null)
    } catch (e: IOException) {
        val cached = dao.getByKey(key) ?: throw e
        Stale(json.decodeFromString(serializer, cached.json), cached.cachedAtMs)
    }
}
