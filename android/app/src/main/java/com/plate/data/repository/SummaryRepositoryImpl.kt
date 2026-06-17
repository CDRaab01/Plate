package com.plate.data.repository

import com.plate.data.remote.ApiService
import com.plate.data.remote.RangeSummary
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SummaryRepositoryImpl @Inject constructor(
    private val api: ApiService,
) : SummaryRepository {

    override suspend fun getSummary(start: String?, end: String?): RangeSummary =
        api.getSummary(start, end)
}
