package com.plate.data.repository

import com.plate.data.local.db.BodyMetricEntity
import com.plate.data.remote.BodyMetricOut
import com.plate.data.remote.WeightTrendOut
import com.plate.util.UnitSystem
import kotlinx.coroutines.flow.Flow

/** Bodyweight weigh-ins (cached for offline trend) + the server-computed trend. */
interface MetricRepository {
    /** Cached weigh-ins, oldest first (canonical kg). The Home trend observes this. */
    val metrics: Flow<List<BodyMetricEntity>>

    /** Pull the server's weigh-ins into the local cache. Best-effort. */
    suspend fun sync()

    /** Log a weigh-in, sending the value in [unit]; caches the result. */
    suspend fun addMetric(date: String, weight: Double, unit: UnitSystem, bodyfat: Double? = null): BodyMetricOut

    /** The server-computed trend (smoothed series + observed rate + on-pace status). */
    suspend fun getTrend(): WeightTrendOut
}
