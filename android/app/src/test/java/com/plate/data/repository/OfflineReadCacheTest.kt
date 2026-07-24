package com.plate.data.repository

import com.plate.data.local.db.BlobCacheDao
import com.plate.data.local.db.BlobCacheEntity
import com.plate.data.remote.ApiService
import com.plate.data.remote.FoodOut
import com.plate.data.remote.GoalOut
import com.plate.data.remote.RangeSummary
import com.plate.data.remote.RecentFoodOut
import com.plate.data.remote.TotalsOut
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * The read-through cache contract, exercised through the three blob-cached repositories
 * (goal / weekly summary / recent foods): success writes through; IOException (server
 * unreachable) serves the cached value; HttpException (server rejection) always rethrows;
 * IOException with no cache rethrows.
 */
private class MapBlobCacheDao : BlobCacheDao {
    val blobs = mutableMapOf<String, BlobCacheEntity>()

    override suspend fun upsert(entry: BlobCacheEntity) {
        blobs[entry.key] = entry
    }

    override suspend fun getByKey(key: String): BlobCacheEntity? = blobs[key]
}

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineReadCacheTest {

    private val blobDao = MapBlobCacheDao()
    private val api: ApiService = mock()
    private val blobCache = BlobCache(blobDao, Json { ignoreUnknownKeys = true })

    private fun goal(kg: Double) = GoalOut(
        id = "g", goalType = "cut", weightKg = kg, heightCm = 180.0, age = 30, sex = "male",
        activityLevel = "moderate", rateKgPerWeek = -0.25, createdAt = "2026-07-01T00:00:00Z",
    )

    private fun totals() = TotalsOut(kcal = 1800.0, proteinG = 150.0, carbsG = 180.0, fatG = 60.0)

    private fun summary() = RangeSummary(
        start = "2026-07-10", end = "2026-07-16", days = emptyList(),
        total = totals(), averages = totals(),
    )

    private fun food(name: String) = FoodOut(
        id = "f-$name", source = "user", name = name,
        kcalPer100g = 100.0, proteinGPer100g = 10.0, carbsGPer100g = 10.0, fatGPer100g = 2.0,
    )

    private fun recent(name: String) =
        RecentFoodOut(food = food(name), lastMeal = "lunch", lastQuantity = 100.0, lastUnit = "g")

    private fun httpException(code: Int = 500) =
        HttpException(Response.error<Any>(code, "boom".toResponseBody("text/plain".toMediaType())))

    // ── goal ─────────────────────────────────────────────────────────────────

    @Test
    fun `getGoal serves the cached goal on IOException`() = runTest {
        whenever(api.getGoal()).thenReturn(goal(90.0))
        val repo = GoalRepositoryImpl(api, blobCache)
        repo.getGoal() // fresh fetch seeds the cache

        doAnswer { throw IOException("offline") }.whenever(api).getGoal()
        assertEquals(90.0, repo.getGoal().weightKg, 0.0)
    }

    @Test
    fun `getGoal rethrows IOException when nothing is cached`() = runTest {
        doAnswer { throw IOException("offline") }.whenever(api).getGoal()
        try {
            GoalRepositoryImpl(api, blobCache).getGoal()
            fail("expected IOException")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun `getGoal rethrows HttpException even with a cache present`() = runTest {
        whenever(api.getGoal()).thenReturn(goal(90.0))
        val repo = GoalRepositoryImpl(api, blobCache)
        repo.getGoal() // seed

        doAnswer { throw httpException(404) }.whenever(api).getGoal()
        try {
            repo.getGoal()
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(404, e.code()) // GoalViewModel's no-goal-yet handling must still see it
        }
    }

    // ── weekly summary ───────────────────────────────────────────────────────

    @Test
    fun `default-window summary serves the cache on IOException`() = runTest {
        whenever(api.getSummary(anyOrNull(), anyOrNull())).thenReturn(summary())
        val repo = SummaryRepositoryImpl(api, blobCache)
        repo.getSummary() // seed

        doAnswer { throw IOException("offline") }.whenever(api).getSummary(anyOrNull(), anyOrNull())
        assertEquals("2026-07-10", repo.getSummary().start)
    }

    @Test
    fun `parameterized summary stays online-only`() = runTest {
        whenever(api.getSummary(anyOrNull(), anyOrNull())).thenReturn(summary())
        val repo = SummaryRepositoryImpl(api, blobCache)
        repo.getSummary() // seed the default-window cache

        doAnswer { throw IOException("offline") }.whenever(api).getSummary(anyOrNull(), anyOrNull())
        try {
            repo.getSummary(start = "2026-07-01", end = "2026-07-31")
            fail("expected IOException — ranged reads never fall back to the weekly blob")
        } catch (_: IOException) {
            // expected
        }
    }

    // ── recent foods ─────────────────────────────────────────────────────────

    @Test
    fun `recentFoods serves the cache on IOException`() = runTest {
        whenever(api.getRecentFoods(any())).thenReturn(listOf(recent("Oats"), recent("Eggs")))
        val repo = FoodRepositoryImpl(api, blobCache)
        assertEquals(2, repo.recentFoods().size) // seed

        doAnswer { throw IOException("offline") }.whenever(api).getRecentFoods(any())
        val cached = repo.recentFoods()
        assertEquals(listOf("Oats", "Eggs"), cached.map { it.food.name })
    }

    @Test
    fun `recentFoods rethrows HttpException even with a cache present`() = runTest {
        whenever(api.getRecentFoods(any())).thenReturn(listOf(recent("Oats")))
        val repo = FoodRepositoryImpl(api, blobCache)
        repo.recentFoods() // seed

        doAnswer { throw httpException() }.whenever(api).getRecentFoods(any())
        try {
            repo.recentFoods()
            fail("expected HttpException")
        } catch (_: HttpException) {
            // expected
        }
    }

    @Test
    fun `search is untouched by the cache layer`() = runTest {
        doAnswer { throw IOException("offline") }.whenever(api).searchFoods(any(), anyOrNull())
        try {
            FoodRepositoryImpl(api, blobCache).search("oats")
            fail("expected IOException — search is online-only")
        } catch (_: IOException) {
            // expected
        }
        verify(api, never()).getRecentFoods(any())
    }
}
