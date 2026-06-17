package com.plate.data.repository

import com.plate.data.remote.RangeSummary

/** Weekly / range summary of logged macros (Phase 8). */
interface SummaryRepository {
    /** Null bounds let the server default to the last 7 days inclusive of today. */
    suspend fun getSummary(start: String? = null, end: String? = null): RangeSummary
}
