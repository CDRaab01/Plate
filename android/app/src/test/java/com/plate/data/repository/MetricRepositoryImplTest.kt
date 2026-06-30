package com.plate.data.repository

import com.plate.data.local.db.BodyMetricDao
import com.plate.data.local.db.BodyMetricEntity
import com.plate.data.remote.ApiService
import com.plate.data.remote.BodyMetricCreate
import com.plate.data.remote.BodyMetricOut
import com.plate.data.remote.WeightTrendOut
import com.plate.util.UnitSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetricRepositoryImplTest {

    private val dao: BodyMetricDao = mock { whenever(it.observeAll()).thenReturn(flowOf(emptyList())) }
    private val api: ApiService = mock()

    private fun repo() = MetricRepositoryImpl(api, dao)

    @Test
    fun `sync pulls server metrics into the cache (canonical kg)`() = runTest {
        whenever(api.getWeightMetrics()).thenReturn(
            listOf(
                BodyMetricOut("1", "u", "2026-06-01", 90.0, 198.4, "lb", null),
                BodyMetricOut("2", "u", "2026-06-02", 89.5, 197.3, "lb", null),
            ),
        )
        repo().sync()

        val captor = argumentCaptor<List<BodyMetricEntity>>()
        verify(dao).upsertAll(captor.capture())
        val cached = captor.firstValue
        assertEquals(2, cached.size)
        // Entity stores canonical kg, not the lb display value.
        assertEquals(90.0, cached[0].weightKg, 0.0)
        assertEquals("2026-06-02", cached[1].date)
    }

    @Test
    fun `addMetric posts with the chosen unit and caches the result`() = runTest {
        whenever(api.addWeightMetric(any())).thenReturn(
            BodyMetricOut("9", "u", "2026-06-30", 89.8, 198.0, "lb", null),
        )
        val out = repo().addMetric("2026-06-30", 198.0, UnitSystem.IMPERIAL)

        val req = argumentCaptor<BodyMetricCreate>()
        verify(api).addWeightMetric(req.capture())
        assertEquals("lb", req.firstValue.unit)
        assertEquals(198.0, req.firstValue.weight, 0.0)
        verify(dao, times(1)).upsert(any())
        assertEquals(89.8, out.weightKg, 0.0)
    }

    @Test
    fun `getTrend delegates to the API`() = runTest {
        val trend = WeightTrendOut(emptyList(), 180.0, -0.5, -0.5, "lb", "on_pace")
        whenever(api.getWeightTrend()).thenReturn(trend)
        assertEquals("on_pace", repo().getTrend().status)
    }
}
