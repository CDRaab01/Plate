package com.plate.data.repository

import com.plate.data.local.db.BlobCacheDao
import com.plate.data.local.db.BlobCacheEntity
import com.plate.data.local.db.BodyMetricDao
import com.plate.data.local.db.BodyMetricEntity
import com.plate.data.remote.ApiService
import com.plate.data.remote.BodyMetricCreate
import com.plate.data.remote.BodyMetricOut
import com.plate.data.remote.WeightTrendOut
import com.plate.util.UnitSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** In-memory [BodyMetricDao] so the queue/promote logic can be exercised without Room. */
private class FakeBodyMetricDao : BodyMetricDao {
    val rows = linkedMapOf<String, BodyMetricEntity>()

    override fun observeAll(): Flow<List<BodyMetricEntity>> = flowOf(rows.values.sortedBy { it.date })

    override suspend fun upsertAll(metrics: List<BodyMetricEntity>) {
        metrics.forEach { rows[it.id] = it }
    }

    override suspend fun upsert(metric: BodyMetricEntity) {
        rows[metric.id] = metric
    }

    override suspend fun getUnsynced(): List<BodyMetricEntity> =
        rows.values.filter { it.syncPending }.sortedBy { it.date }

    override suspend fun deleteById(id: String) {
        rows.remove(id)
    }
}

private class FakeBlobCacheDao : BlobCacheDao {
    val blobs = mutableMapOf<String, BlobCacheEntity>()

    override suspend fun upsert(entry: BlobCacheEntity) {
        blobs[entry.key] = entry
    }

    override suspend fun getByKey(key: String): BlobCacheEntity? = blobs[key]
}

@OptIn(ExperimentalCoroutinesApi::class)
class MetricRepositoryImplTest {

    private val dao = FakeBodyMetricDao()
    private val blobDao = FakeBlobCacheDao()
    private val api: ApiService = mock()
    private val json = Json { ignoreUnknownKeys = true }

    private fun repo() = MetricRepositoryImpl(api, dao, BlobCache(blobDao, json))

    private fun serverMetric(id: String, date: String, weightKg: Double) =
        BodyMetricOut(id, "u", date, weightKg, weightKg, "kg", null)

    private fun httpException(code: Int = 422) =
        HttpException(Response.error<Any>(code, "rejected".toResponseBody("text/plain".toMediaType())))

    @Test
    fun `sync pulls server metrics into the cache (canonical kg)`() = runTest {
        whenever(api.getWeightMetrics()).thenReturn(
            listOf(serverMetric("1", "2026-06-01", 90.0), serverMetric("2", "2026-06-02", 89.5)),
        )
        repo().sync()

        assertEquals(2, dao.rows.size)
        val first = dao.rows.getValue("1")
        assertEquals(90.0, first.weightKg, 0.0)
        assertEquals("1", first.serverId)
        assertTrue(!first.syncPending)
    }

    @Test
    fun `addMetric offline converts to kg, queues locally, and does not throw`() = runTest {
        doAnswer { throw IOException("offline") }.whenever(api).addWeightMetric(any())

        repo().addMetric("2026-06-30", 198.0, UnitSystem.IMPERIAL)

        val queued = dao.rows.values.single()
        // Canonical kg in the queue — never the raw lb number (a unit-toggle can't corrupt it).
        assertEquals(198.0 * 0.45359237, queued.weightKg, 1e-9)
        assertTrue(queued.syncPending)
        assertNull(queued.serverId)
    }

    @Test
    fun `addMetric online posts kg and promotes the local row to the server row`() = runTest {
        whenever(api.addWeightMetric(any())).thenReturn(serverMetric("9", "2026-06-30", 89.811))
        repo().addMetric("2026-06-30", 198.0, UnitSystem.IMPERIAL)

        val req = argumentCaptor<BodyMetricCreate>()
        verify(api).addWeightMetric(req.capture())
        // The wire request is explicit canonical kg regardless of the entry unit.
        assertEquals("kg", req.firstValue.unit)
        assertEquals(198.0 * 0.45359237, req.firstValue.weight, 1e-9)

        val row = dao.rows.values.single()
        assertEquals("9", row.id)
        assertEquals("9", row.serverId)
        assertTrue(!row.syncPending)
    }

    @Test
    fun `addMetric rejection (HttpException) drops the queued row and rethrows`() = runTest {
        doAnswer { throw httpException() }.whenever(api).addWeightMetric(any())

        try {
            repo().addMetric("2026-06-30", 5000.0, UnitSystem.IMPERIAL)
            fail("expected HttpException")
        } catch (_: HttpException) {
            // expected — a reachable server's rejection keeps erroring
        }
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `sync drains a queued weigh-in in kg and promotes it`() = runTest {
        dao.rows["local-1"] = BodyMetricEntity(
            id = "local-1", date = "2026-06-30", weightKg = 89.8, bodyfat = null,
            serverId = null, syncPending = true,
        )
        whenever(api.addWeightMetric(any())).thenReturn(serverMetric("42", "2026-06-30", 89.8))
        whenever(api.getWeightMetrics()).thenReturn(listOf(serverMetric("42", "2026-06-30", 89.8)))

        repo().sync()

        val req = argumentCaptor<BodyMetricCreate>()
        verify(api).addWeightMetric(req.capture())
        assertEquals("kg", req.firstValue.unit)
        assertEquals(89.8, req.firstValue.weight, 0.0)

        assertNull(dao.rows["local-1"])
        val promoted = dao.rows.getValue("42")
        assertTrue(!promoted.syncPending)
        assertEquals("42", promoted.serverId)
    }

    @Test
    fun `drain stops on IOException and keeps rows queued`() = runTest {
        dao.rows["local-1"] = BodyMetricEntity("local-1", "2026-06-29", 90.0, null, null, true)
        dao.rows["local-2"] = BodyMetricEntity("local-2", "2026-06-30", 89.5, null, null, true)
        doAnswer { throw IOException("offline") }.whenever(api).addWeightMetric(any())
        doAnswer { throw IOException("offline") }.whenever(api).getWeightMetrics()

        try {
            repo().sync()
            fail("expected IOException from the pull half of sync")
        } catch (_: IOException) {
            // expected — callers treat sync as best-effort
        }
        assertEquals(2, dao.rows.values.count { it.syncPending })
    }

    @Test
    fun `getTrendStale serves the cached trend on IOException`() = runTest {
        val trend = WeightTrendOut(emptyList(), 180.0, -0.5, -0.5, "lb", "on_pace")
        whenever(api.getWeightTrend()).thenReturn(trend)
        val repo = repo()

        // Fresh fetch caches; asOfMs null means fresh.
        assertNull(repo.getTrendStale().asOfMs)

        doAnswer { throw IOException("offline") }.whenever(api).getWeightTrend()
        val stale = repo.getTrendStale()
        assertEquals("on_pace", stale.value.status)
        assertNotNull(stale.asOfMs)
    }

    @Test
    fun `getTrendStale rethrows an HttpException even with a cache present`() = runTest {
        val trend = WeightTrendOut(emptyList(), 180.0, -0.5, -0.5, "lb", "on_pace")
        whenever(api.getWeightTrend()).thenReturn(trend)
        val repo = repo()
        repo.getTrendStale() // seed the cache

        doAnswer { throw httpException(500) }.whenever(api).getWeightTrend()
        try {
            repo.getTrendStale()
            fail("expected HttpException")
        } catch (_: HttpException) {
            // expected — only unreachability degrades to the cache
        }
    }
}
