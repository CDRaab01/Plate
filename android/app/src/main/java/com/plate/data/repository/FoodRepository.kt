package com.plate.data.repository

import com.plate.data.remote.FoodOut

/** Food search backed by the server's local-cache-first endpoint. */
interface FoodRepository {
    suspend fun search(query: String): List<FoodOut>

    /** Resolve a scanned barcode to a food (Phase 4). Throws on an unknown barcode (HTTP 404). */
    suspend fun lookupBarcode(code: String): FoodOut
}
