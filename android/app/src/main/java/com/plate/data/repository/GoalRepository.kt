package com.plate.data.repository

import com.plate.data.remote.GoalOut
import com.plate.data.remote.GoalUpsertRequest

/** The user's active goal: read and set. */
interface GoalRepository {
    suspend fun getGoal(): GoalOut
    suspend fun setGoal(request: GoalUpsertRequest): GoalOut
}
