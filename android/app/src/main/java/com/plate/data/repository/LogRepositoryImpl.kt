package com.plate.data.repository

import com.plate.data.local.db.CachedDayEntity
import com.plate.data.local.db.DiaryDao
import com.plate.data.local.db.PendingQuickAddEntity
import com.plate.data.remote.ApiService
import com.plate.data.remote.DailyLog
import com.plate.data.remote.LogEntryBatchCreate
import com.plate.data.remote.LogEntryCreate
import com.plate.data.remote.LogEntryOut
import com.plate.data.remote.LogEntryUpdate
import com.plate.data.remote.MealGroup
import com.plate.data.remote.CopyDayRequest
import com.plate.data.remote.QuickAddRequest
import com.plate.data.remote.RecipeLogRequest
import com.plate.data.remote.TotalsOut
import com.plate.util.AppPreferences
import com.plate.util.nudges.NudgeLogic
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-tolerant diary repository (CLAUDE.md §2 — mirror Spotter's local-first sync). Reads are
 * served from the server and cached to Room so the diary still renders when offline; quick-adds made
 * offline are queued in Room and pushed on reconnect. Food-by-id and recipe logging need server-side
 * portion scaling, so they remain online-only (they throw when offline) — matching Spotter leaving
 * its non-core writes online.
 */
@Singleton
class LogRepositoryImpl @Inject constructor(
    private val api: ApiService,
    private val diaryDao: DiaryDao,
    private val json: Json,
    private val appPreferences: AppPreferences,
) : LogRepository {

    /**
     * Record that the user logged food today, so the evening "nothing logged today" nudge can skip
     * itself. Best-effort — a preference write must never fail a log.
     */
    private suspend fun markLoggedToday() {
        runCatching {
            appPreferences.setLastLogEpochDay(
                NudgeLogic.todayEpochDay(System.currentTimeMillis(), ZoneId.systemDefault()),
            )
        }
    }

    override suspend fun getDay(date: String): DailyLog = getDayStale(date).value

    override suspend fun getDayStale(date: String): Stale<DailyLog> {
        // Flush anything queued offline first so a fresh fetch already reflects it on the server.
        runCatching { syncPending() }
        var asOfMs: Long? = null
        val serverDay = try {
            api.getDay(date).also {
                diaryDao.upsertDay(
                    CachedDayEntity(date, json.encodeToString(DailyLog.serializer(), it), System.currentTimeMillis()),
                )
            }
        } catch (e: IOException) {
            // Only unreachability degrades to the cache; a reachable server's rejection
            // (HttpException) surfaces as the error it is.
            val cached = diaryDao.getCachedDay(date)
                ?: throw e // offline with no cache for this day — surface the error
            asOfMs = cached.cachedAtMs
            json.decodeFromString(DailyLog.serializer(), cached.json)
        }
        val pending = diaryDao.pendingForDate(date)
        val day = if (pending.isEmpty()) serverDay else mergePending(serverDay, pending)
        return Stale(day, asOfMs)
    }

    override suspend fun addEntry(
        foodId: String,
        date: String,
        meal: String,
        quantity: Double,
        unit: String,
    ): LogEntryOut = api.createLogEntry(LogEntryCreate(foodId, date, meal, quantity, unit))
        .also { markLoggedToday() }

    override suspend fun addEntries(
        date: String,
        meal: String,
        items: List<BatchLogItem>,
    ): List<LogEntryOut> = api.createLogEntriesBatch(
        LogEntryBatchCreate(items.map { LogEntryCreate(it.foodId, date, meal, it.quantity, it.unit) }),
    ).also { markLoggedToday() }

    override suspend fun updateEntry(
        id: String,
        quantity: Double?,
        unit: String?,
        meal: String?,
    ): LogEntryOut = api.updateLogEntry(id, LogEntryUpdate(meal = meal, quantity = quantity, unit = unit))

    override suspend fun deleteEntry(id: String) = api.deleteLogEntry(id)

    override suspend fun quickAdd(
        date: String,
        meal: String,
        name: String?,
        kcal: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
    ): LogEntryOut {
        val request = QuickAddRequest(
            date = date,
            meal = meal,
            name = name,
            kcal = kcal,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
        )
        markLoggedToday()
        return try {
            api.quickAdd(request)
        } catch (_: IOException) {
            // Server unreachable: queue it locally and surface it immediately as a synthetic
            // entry. It syncs on reconnect (NetworkSyncObserver) or the next getDay. A rejection
            // from a *reachable* server (HttpException) rethrows instead — never queue a reject.
            val pending = PendingQuickAddEntity(
                localId = "pending-${UUID.randomUUID()}",
                date = date,
                meal = meal,
                name = name,
                kcal = kcal,
                proteinG = proteinG,
                carbsG = carbsG,
                fatG = fatG,
                createdAt = System.currentTimeMillis(),
            )
            diaryDao.insertPending(pending)
            pending.toLogEntry()
        }
    }

    override suspend fun logRecipe(recipeId: String, date: String, meal: String): List<LogEntryOut> =
        api.logRecipe(recipeId, RecipeLogRequest(date = date, meal = meal)).also { markLoggedToday() }

    override suspend fun copyDay(fromDate: String, toDate: String): List<LogEntryOut> =
        api.copyDay(CopyDayRequest(fromDate = fromDate, toDate = toDate)).also { markLoggedToday() }

    override suspend fun syncPending() {
        for (entry in diaryDao.allPending()) {
            try {
                api.quickAdd(
                    QuickAddRequest(
                        date = entry.date,
                        meal = entry.meal,
                        name = entry.name,
                        kcal = entry.kcal,
                        proteinG = entry.proteinG,
                        carbsG = entry.carbsG,
                        fatG = entry.fatG,
                    ),
                )
                diaryDao.deletePending(entry.localId)
            } catch (_: Exception) {
                // Still offline (or the server rejected this one) — stop and retry on next trigger.
                return
            }
        }
    }

    /** Fold queued offline quick-adds into a server/cached [DailyLog]: add to their meal groups and
     * roll the per-meal and day totals forward so the diary's numbers stay consistent. */
    private fun mergePending(day: DailyLog, pending: List<PendingQuickAddEntity>): DailyLog {
        val byMeal = pending.groupBy { it.meal }
        val meals = day.meals.map { group ->
            val extras = byMeal[group.meal].orEmpty()
            if (extras.isEmpty()) {
                group
            } else {
                MealGroup(
                    meal = group.meal,
                    entries = group.entries + extras.map { it.toLogEntry() },
                    totals = group.totals.plus(extras),
                )
            }
        }
        return day.copy(meals = meals, totals = day.totals.plus(pending))
    }
}

private fun PendingQuickAddEntity.toLogEntry(): LogEntryOut = LogEntryOut(
    id = localId,
    foodId = null,
    foodName = name,
    date = date,
    meal = meal,
    quantity = 1.0,
    unit = "serving",
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
)

private fun TotalsOut.plus(entries: List<PendingQuickAddEntity>): TotalsOut = copy(
    kcal = kcal + entries.sumOf { it.kcal },
    proteinG = proteinG + entries.sumOf { it.proteinG },
    carbsG = carbsG + entries.sumOf { it.carbsG },
    fatG = fatG + entries.sumOf { it.fatG },
)
