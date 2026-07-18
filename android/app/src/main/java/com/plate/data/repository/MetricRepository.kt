package com.plate.data.repository

import com.plate.data.local.db.BodyMetricEntity
import com.plate.data.remote.WeightTrendOut
import com.plate.util.UnitSystem
import kotlinx.coroutines.flow.Flow

/** Bodyweight weigh-ins (offline write-through queue + cache) + the server-computed trend. */
interface MetricRepository {
    /** Cached weigh-ins, oldest first (canonical kg), including any offline-queued ones. */
    val metrics: Flow<List<BodyMetricEntity>>

    /** Drain offline-queued weigh-ins, then pull the server's into the local cache. */
    suspend fun sync()

    /**
     * Log a weigh-in entered in [unit]. Persists locally first (canonical kg) and pushes
     * best-effort — with the server unreachable it queues silently for [sync] instead of throwing;
     * a *rejected* write (HttpException) still throws.
     */
    suspend fun addMetric(date: String, weight: Double, unit: UnitSystem, bodyfat: Double? = null)

    /** The server-computed trend (smoothed series + observed rate + on-pace status). */
    suspend fun getTrend(): WeightTrendOut

    /** [getTrend] with offline fallback: served from the read cache (stale) when unreachable. */
    suspend fun getTrendStale(): Stale<WeightTrendOut> = Stale(getTrend(), null)
}
