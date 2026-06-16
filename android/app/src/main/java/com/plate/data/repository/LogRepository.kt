package com.plate.data.repository

import com.plate.data.remote.DailyLog
import com.plate.data.remote.LogEntryOut

/** The day's food log: read a day, and create / edit / delete entries. */
interface LogRepository {
    suspend fun getDay(date: String): DailyLog

    suspend fun addEntry(
        foodId: String,
        date: String,
        meal: String,
        quantity: Double,
        unit: String,
    ): LogEntryOut

    suspend fun updateEntry(
        id: String,
        quantity: Double? = null,
        unit: String? = null,
        meal: String? = null,
    ): LogEntryOut

    suspend fun deleteEntry(id: String)
}
