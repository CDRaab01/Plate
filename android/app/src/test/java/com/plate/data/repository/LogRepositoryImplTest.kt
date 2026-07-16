package com.plate.data.repository

import com.plate.data.local.db.CachedDayEntity
import com.plate.data.local.db.DiaryDao
import com.plate.data.local.db.PendingQuickAddEntity
import com.plate.data.remote.ApiService
import com.plate.data.remote.DailyLog
import com.plate.data.remote.LogEntryOut
import com.plate.data.remote.MealGroup
import com.plate.data.remote.QuickAddRequest
import com.plate.data.remote.TotalsOut
import android.app.Application
import com.plate.util.AppPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** In-memory [DiaryDao] so the offline cache/queue logic can be exercised without Room. */
private class FakeDiaryDao : DiaryDao {
    val days = mutableMapOf<String, CachedDayEntity>()
    val pending = mutableListOf<PendingQuickAddEntity>()

    override suspend fun upsertDay(day: CachedDayEntity) {
        days[day.date] = day
    }

    override suspend fun getCachedDay(date: String): CachedDayEntity? = days[date]

    override suspend fun insertPending(entry: PendingQuickAddEntity) {
        pending.add(entry)
    }

    override suspend fun pendingForDate(date: String): List<PendingQuickAddEntity> =
        pending.filter { it.date == date }

    override suspend fun allPending(): List<PendingQuickAddEntity> = pending.toList()

    override suspend fun deletePending(localId: String) {
        pending.removeAll { it.localId == localId }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LogRepositoryImplTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val dao = FakeDiaryDao()
    private val appPreferences =
        AppPreferences(ApplicationProvider.getApplicationContext())

    private fun zeroTotals() = TotalsOut(kcal = 0.0, proteinG = 0.0, carbsG = 0.0, fatG = 0.0)

    private fun emptyDay(date: String) = DailyLog(
        date = date,
        meals = listOf(
            MealGroup("breakfast", emptyList(), zeroTotals()),
            MealGroup("lunch", emptyList(), zeroTotals()),
            MealGroup("dinner", emptyList(), zeroTotals()),
            MealGroup("snack", emptyList(), zeroTotals()),
        ),
        totals = zeroTotals(),
        targets = TotalsOut(kcal = 2000.0, proteinG = 150.0, carbsG = 200.0, fatG = 60.0),
    )

    @Test
    fun `quickAdd offline queues and returns a synthetic entry`() = runTest {
        val api = mock<ApiService>()
        doThrow(RuntimeException("offline")).whenever(api).quickAdd(any())
        val repo = LogRepositoryImpl(api, dao, json, appPreferences)

        val entry = repo.quickAdd("2026-06-18", "lunch", "Protein bar", 200.0, 20.0, 25.0, 6.0)

        assertNull(entry.foodId)
        assertEquals("Protein bar", entry.foodName)
        assertEquals(200.0, entry.kcal, 0.0)
        assertEquals(1, dao.pending.size)
        assertEquals("lunch", dao.pending.first().meal)
    }

    @Test
    fun `getDay offline falls back to cache and merges a queued quick-add`() = runTest {
        // Seed a cached day + a pending offline quick-add for it.
        dao.days["2026-06-18"] = CachedDayEntity(
            "2026-06-18",
            json.encodeToString(DailyLog.serializer(), emptyDay("2026-06-18")),
        )
        dao.pending.add(
            PendingQuickAddEntity(
                localId = "pending-1", date = "2026-06-18", meal = "lunch", name = "Protein bar",
                kcal = 200.0, proteinG = 20.0, carbsG = 25.0, fatG = 6.0, createdAt = 1L,
            ),
        )
        val api = mock<ApiService>()
        doThrow(RuntimeException("offline")).whenever(api).getDay(any())
        doThrow(RuntimeException("offline")).whenever(api).quickAdd(any())
        val repo = LogRepositoryImpl(api, dao, json, appPreferences)

        val day = repo.getDay("2026-06-18")

        // Day totals reflect the queued entry, and it shows under its meal.
        assertEquals(200.0, day.totals.kcal, 0.0)
        val lunch = day.meals.first { it.meal == "lunch" }
        assertEquals(1, lunch.entries.size)
        assertEquals("Protein bar", lunch.entries.first().foodName)
        assertEquals(200.0, lunch.totals.kcal, 0.0)
    }

    @Test
    fun `getDay online flushes the offline queue and caches the day`() = runTest {
        dao.pending.add(
            PendingQuickAddEntity(
                localId = "pending-1", date = "2026-06-18", meal = "lunch", name = "Protein bar",
                kcal = 200.0, proteinG = 20.0, carbsG = 25.0, fatG = 6.0, createdAt = 1L,
            ),
        )
        val api = mock<ApiService>()
        whenever(api.quickAdd(any())).thenReturn(syncedEntry())
        whenever(api.getDay("2026-06-18")).thenReturn(emptyDay("2026-06-18"))
        val repo = LogRepositoryImpl(api, dao, json, appPreferences)

        val day = repo.getDay("2026-06-18")

        // The queue drained (so no double-count) and the fresh server day was cached.
        assertTrue(dao.pending.isEmpty())
        assertEquals(0.0, day.totals.kcal, 0.0)
        assertTrue(dao.days.containsKey("2026-06-18"))
    }

    private fun syncedEntry() = LogEntryOut(
        id = "server-1", foodId = null, foodName = "Protein bar", date = "2026-06-18",
        meal = "lunch", quantity = 1.0, unit = "serving", kcal = 200.0,
        proteinG = 20.0, carbsG = 25.0, fatG = 6.0,
    )
}
