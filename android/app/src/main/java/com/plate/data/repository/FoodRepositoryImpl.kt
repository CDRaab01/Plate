package com.plate.data.repository

import com.plate.data.remote.ApiService
import com.plate.data.remote.FoodCreateRequest
import com.plate.data.remote.FoodOut
import com.plate.data.remote.PhotoEstimateResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FoodRepositoryImpl @Inject constructor(
    private val api: ApiService,
) : FoodRepository {
    override suspend fun search(query: String): List<FoodOut> = api.searchFoods(query)

    override suspend fun lookupBarcode(code: String): FoodOut = api.lookupBarcode(code)

    override suspend fun createFood(req: FoodCreateRequest): FoodOut = api.createFood(req)

    override suspend fun estimatePhoto(image: ByteArray, mimeType: String): PhotoEstimateResponse {
        val body = image.toRequestBody(mimeType.toMediaTypeOrNull())
        // The server keys the upload on "image" (the multipart field name); the filename is cosmetic.
        val part = MultipartBody.Part.createFormData("image", "meal.jpg", body)
        return api.estimatePhoto(part)
    }
}
