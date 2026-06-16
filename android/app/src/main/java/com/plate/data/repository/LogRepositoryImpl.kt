package com.plate.data.repository

import com.plate.data.remote.ApiService
import com.plate.data.remote.DailyLog
import com.plate.data.remote.LogEntryCreate
import com.plate.data.remote.LogEntryOut
import com.plate.data.remote.LogEntryUpdate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogRepositoryImpl @Inject constructor(
    private val api: ApiService,
) : LogRepository {

    override suspend fun getDay(date: String): DailyLog = api.getDay(date)

    override suspend fun addEntry(
        foodId: String,
        date: String,
        meal: String,
        quantity: Double,
        unit: String,
    ): LogEntryOut = api.createLogEntry(LogEntryCreate(foodId, date, meal, quantity, unit))

    override suspend fun updateEntry(
        id: String,
        quantity: Double?,
        unit: String?,
        meal: String?,
    ): LogEntryOut = api.updateLogEntry(id, LogEntryUpdate(meal = meal, quantity = quantity, unit = unit))

    override suspend fun deleteEntry(id: String) = api.deleteLogEntry(id)
}
