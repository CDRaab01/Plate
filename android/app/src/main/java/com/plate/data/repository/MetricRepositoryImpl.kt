package com.plate.data.repository

import com.plate.data.local.db.BodyMetricDao
import com.plate.data.local.db.BodyMetricEntity
import com.plate.data.remote.ApiService
import com.plate.data.remote.BodyMetricCreate
import com.plate.data.remote.BodyMetricOut
import com.plate.data.remote.WeightTrendOut
import com.plate.util.Units
import com.plate.util.UnitSystem
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bodyweight log — **offline-capable, write-through** (mirrors Spotter's MetricRepository): a
 * weigh-in lands in Room immediately (so it is never lost, even offline), is pushed best-effort,
 * and drains from the `syncPending` queue on the next [sync] / connectivity regain. Room is the
 * read source of truth; the network is the eventual authority for synced rows.
 *
 * Unit rule (CLAUDE.md invariant #1): the weigh-in is converted to **canonical kg before it is
 * inserted**, and the drain reconstructs the request in kg explicitly — so a later lb↔kg toggle
 * can never corrupt an entry queued under the old unit.
 */
@Singleton
class MetricRepositoryImpl @Inject constructor(
    private val api: ApiService,
    private val dao: BodyMetricDao,
    private val blobCache: BlobCache,
) : MetricRepository {

    override val metrics: Flow<List<BodyMetricEntity>> = dao.observeAll()

    override suspend fun sync() {
        drainPending()
        val remote = api.getWeightMetrics()
        // Synced rows are keyed by their server id, so this REPLACEs them without duplicating.
        dao.upsertAll(remote.map { it.toEntity() })
    }

    override suspend fun addMetric(date: String, weight: Double, unit: UnitSystem, bodyfat: Double?) {
        val weightKg = if (unit == UnitSystem.IMPERIAL) Units.lbToKg(weight) else weight
        val localId = "local-${UUID.randomUUID()}"
        dao.upsert(
            BodyMetricEntity(
                id = localId, date = date, weightKg = weightKg, bodyfat = bodyfat,
                serverId = null, syncPending = true,
            ),
        )
        try {
            // Best-effort immediate push; on success promote the local row to the acknowledged one.
            promote(localId, api.addWeightMetric(kgCreate(date, weightKg, bodyfat)))
        } catch (_: IOException) {
            // Server unreachable — the row stays queued and drains on the next sync/reconnect.
        } catch (e: Exception) {
            // The server *rejected* it (e.g. out-of-bounds 422). Drop the queued row and surface
            // the error — silently retrying a rejected value forever would be worse.
            dao.deleteById(localId)
            throw e
        }
    }

    override suspend fun getTrend(): WeightTrendOut = getTrendStale().value

    override suspend fun getTrendStale(): Stale<WeightTrendOut> =
        blobCache.readThrough("weight_trend", WeightTrendOut.serializer()) { api.getWeightTrend() }

    /** Push every offline weigh-in still queued; stops when the server is unreachable. */
    private suspend fun drainPending() {
        for (local in dao.getUnsynced()) {
            val saved = try {
                api.addWeightMetric(kgCreate(local.date, local.weightKg, local.bodyfat))
            } catch (_: IOException) {
                return // unreachable — the rest would fail too; retry on the next trigger
            } catch (_: Exception) {
                continue // rejected this one; leave it pending, don't block the rest
            }
            promote(local.id, saved)
        }
    }

    /** Replace a local-id offline row with its acknowledged server row (PK becomes the server id). */
    private suspend fun promote(localId: String, saved: BodyMetricOut) {
        dao.deleteById(localId)
        dao.upsert(saved.toEntity())
    }

    /** Always send canonical kg on the wire — the queue stores kg, so the request is unit-stable. */
    private fun kgCreate(date: String, weightKg: Double, bodyfat: Double?) =
        BodyMetricCreate(date = date, weight = weightKg, unit = "kg", bodyfat = bodyfat)

    private fun BodyMetricOut.toEntity() = BodyMetricEntity(
        id = id, date = date, weightKg = weightKg, bodyfat = bodyfat,
        serverId = id, syncPending = false,
    )
}
