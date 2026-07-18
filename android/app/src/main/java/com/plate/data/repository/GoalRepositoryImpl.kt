package com.plate.data.repository

import com.plate.data.remote.ApiService
import com.plate.data.remote.GoalOut
import com.plate.data.remote.GoalUpsertRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoalRepositoryImpl @Inject constructor(
    private val api: ApiService,
    private val blobCache: BlobCache,
) : GoalRepository {

    /**
     * Read-through cached: with the server unreachable the last-known goal is served so the goal
     * screen still renders. A reachable server's rejection (e.g. the 404 meaning "no goal yet")
     * still surfaces as-is.
     */
    override suspend fun getGoal(): GoalOut =
        blobCache.readThrough("goal", GoalOut.serializer()) { api.getGoal() }.value

    override suspend fun setGoal(request: GoalUpsertRequest): GoalOut = api.setGoal(request)
}
