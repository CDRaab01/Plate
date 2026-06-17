package com.plate.data.repository

import com.plate.data.remote.ApiService
import com.plate.data.remote.DailyLog
import com.plate.data.remote.LogEntryCreate
import com.plate.data.remote.LogEntryOut
import com.plate.data.remote.LogEntryUpdate
import com.plate.data.remote.QuickAddRequest
import com.plate.data.remote.RecipeLogRequest
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

    override suspend fun quickAdd(
        date: String,
        meal: String,
        name: String?,
        kcal: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
    ): LogEntryOut = api.quickAdd(
        QuickAddRequest(
            date = date,
            meal = meal,
            name = name,
            kcal = kcal,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
        ),
    )

    override suspend fun logRecipe(recipeId: String, date: String, meal: String): List<LogEntryOut> =
        api.logRecipe(recipeId, RecipeLogRequest(date = date, meal = meal))
}
