package com.plate.data.repository

import com.plate.data.remote.ApiService
import com.plate.data.remote.GoalOut
import com.plate.data.remote.GoalUpsertRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoalRepositoryImpl @Inject constructor(
    private val api: ApiService,
) : GoalRepository {

    override suspend fun getGoal(): GoalOut = api.getGoal()

    override suspend fun setGoal(request: GoalUpsertRequest): GoalOut = api.setGoal(request)
}
