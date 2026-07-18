package com.plate.data.repository

import com.plate.data.remote.ApiService
import com.plate.data.remote.RangeSummary
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SummaryRepositoryImpl @Inject constructor(
    private val api: ApiService,
    private val blobCache: BlobCache,
) : SummaryRepository {

    /**
     * Only the default window (both bounds null → the server's last-7-days) is read-through
     * cached; parameterized ranges (e.g. the calendar's month pages) stay online-only — caching
     * every ad-hoc range would multiply blob keys for reads that are fine to fail loudly.
     */
    override suspend fun getSummary(start: String?, end: String?): RangeSummary =
        if (start == null && end == null) {
            blobCache.readThrough("weekly_summary", RangeSummary.serializer()) {
                api.getSummary(null, null)
            }.value
        } else {
            api.getSummary(start, end)
        }
}
