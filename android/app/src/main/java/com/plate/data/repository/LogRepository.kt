package com.plate.data.repository

import com.plate.data.remote.DailyLog
import com.plate.data.remote.LogEntryOut

/** One food + portion in a multi-select batch add (the meal + date are shared across the batch). */
data class BatchLogItem(
    val foodId: String,
    val quantity: Double,
    val unit: String,
)

/** The day's food log: read a day, and create / edit / delete entries. */
interface LogRepository {
    suspend fun getDay(date: String): DailyLog

    /**
     * [getDay] with the cache provenance surfaced: `asOfMs` is non-null when the day was served
     * from the offline cache (its fetch time), letting Home/Diary show a stale banner.
     */
    suspend fun getDayStale(date: String): Stale<DailyLog> = Stale(getDay(date), null)

    suspend fun addEntry(
        foodId: String,
        date: String,
        meal: String,
        quantity: Double,
        unit: String,
    ): LogEntryOut

    /**
     * Log several foods into one day/meal in a single call (the food-search multi-select add).
     * Server-side portion scaling is required, so — like [addEntry] and [logRecipe] — this is
     * online-only. All-or-nothing: the server rejects the whole batch if any food can't be logged.
     * The default falls back to sequential [addEntry]s; the real repo overrides it with one request.
     */
    suspend fun addEntries(
        date: String,
        meal: String,
        items: List<BatchLogItem>,
    ): List<LogEntryOut> = items.map { addEntry(it.foodId, date, meal, it.quantity, it.unit) }

    suspend fun updateEntry(
        id: String,
        quantity: Double? = null,
        unit: String? = null,
        meal: String? = null,
    ): LogEntryOut

    suspend fun deleteEntry(id: String)

    /** Quick add: log raw macros directly (no source food). */
    suspend fun quickAdd(
        date: String,
        meal: String,
        name: String?,
        kcal: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
    ): LogEntryOut

    /** Expand a saved recipe into the given day's meal. */
    suspend fun logRecipe(recipeId: String, date: String, meal: String): List<LogEntryOut>

    /** Copy every entry from [fromDate] into [toDate] (additive) — the "copy yesterday" quick-log. */
    suspend fun copyDay(fromDate: String, toDate: String): List<LogEntryOut>

    /** Push any quick-adds that were queued while offline. Best-effort; safe to call repeatedly. */
    suspend fun syncPending()
}
