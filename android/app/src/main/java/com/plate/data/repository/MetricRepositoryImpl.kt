package com.plate.data.repository

import com.plate.data.local.db.BodyMetricDao
import com.plate.data.local.db.BodyMetricEntity
import com.plate.data.remote.ApiService
import com.plate.data.remote.BodyMetricCreate
import com.plate.data.remote.BodyMetricOut
import com.plate.data.remote.WeightTrendOut
import com.plate.util.UnitSystem
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetricRepositoryImpl @Inject constructor(
    private val api: ApiService,
    private val dao: BodyMetricDao,
) : MetricRepository {

    override val metrics: Flow<List<BodyMetricEntity>> = dao.observeAll()

    override suspend fun sync() {
        // Best-effort: an offline failure leaves the existing cache in place.
        val remote = api.getWeightMetrics()
        dao.upsertAll(remote.map { it.toEntity() })
    }

    override suspend fun addMetric(
        date: String,
        weight: Double,
        unit: UnitSystem,
        bodyfat: Double?,
    ): BodyMetricOut {
        val out = api.addWeightMetric(
            BodyMetricCreate(date = date, weight = weight, unit = unit.weightUnit, bodyfat = bodyfat),
        )
        dao.upsert(out.toEntity())
        return out
    }

    override suspend fun getTrend(): WeightTrendOut = api.getWeightTrend()

    private fun BodyMetricOut.toEntity() =
        BodyMetricEntity(id = id, date = date, weightKg = weightKg, bodyfat = bodyfat)
}
