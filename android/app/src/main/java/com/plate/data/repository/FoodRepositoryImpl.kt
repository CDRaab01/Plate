package com.plate.data.repository

import com.plate.data.remote.ApiService
import com.plate.data.remote.FoodOut
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FoodRepositoryImpl @Inject constructor(
    private val api: ApiService,
) : FoodRepository {
    override suspend fun search(query: String): List<FoodOut> = api.searchFoods(query)
}
