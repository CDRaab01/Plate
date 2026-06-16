package com.plate.data.repository

import com.plate.data.remote.FoodOut

/** Food search backed by the server's local-cache-first endpoint. */
interface FoodRepository {
    suspend fun search(query: String): List<FoodOut>
}
