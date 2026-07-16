package com.plate.data.repository

import com.plate.data.remote.FoodCreateRequest
import com.plate.data.remote.FoodOut
import com.plate.data.remote.PhotoEstimateResponse
import com.plate.data.remote.RecentFoodOut

/** Food search backed by the server's local-cache-first endpoint. */
interface FoodRepository {
    suspend fun search(query: String): List<FoodOut>

    /** Recently-logged foods, most-recent first, each with the last portion used (one-tap re-log). */
    suspend fun recentFoods(limit: Int = 20): List<RecentFoodOut>

    /** Resolve a scanned barcode to a food (Phase 4). Throws on an unknown barcode (HTTP 404). */
    suspend fun lookupBarcode(code: String): FoodOut

    /** Create a user-defined custom food (Phase 6: persists a confirmed photo estimate). */
    suspend fun createFood(req: FoodCreateRequest): FoodOut

    /**
     * Estimate the foods + macros in a meal photo (Phase 6). The result is an editable draft the
     * user confirms before logging — nothing is logged by this call.
     */
    suspend fun estimatePhoto(image: ByteArray, mimeType: String): PhotoEstimateResponse

    /**
     * Read a nutrition-label photo into an editable draft (one food = one serving). Higher-accuracy
     * than [estimatePhoto]; likewise never logs anything by itself.
     */
    suspend fun estimateLabel(image: ByteArray, mimeType: String): PhotoEstimateResponse

    /**
     * Parse on-device-recognized speech (e.g. "two eggs and a banana") into an editable food draft.
     * Only the text is sent; the server resolves foods + macros. Never logs anything by itself.
     */
    suspend fun parseVoice(text: String): PhotoEstimateResponse
}
