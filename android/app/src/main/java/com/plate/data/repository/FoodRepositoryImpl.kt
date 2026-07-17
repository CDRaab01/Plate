package com.plate.data.repository

import com.plate.data.remote.ApiService
import com.plate.data.remote.FoodCreateRequest
import com.plate.data.remote.FoodOut
import com.plate.data.remote.PhotoEstimateResponse
import com.plate.data.remote.RecentFoodOut
import com.plate.data.remote.VoiceParseRequest
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FoodRepositoryImpl @Inject constructor(
    private val api: ApiService,
    private val blobCache: BlobCache,
) : FoodRepository {
    override suspend fun search(query: String): List<FoodOut> = api.searchFoods(query)

    // Read-through cached (one blob, keyed ignoring [limit] — every caller uses the default), so
    // the one-tap re-log chips survive the server being unreachable. Search stays online-only.
    override suspend fun recentFoods(limit: Int): List<RecentFoodOut> =
        blobCache.readThrough("recent_foods", ListSerializer(RecentFoodOut.serializer())) {
            api.getRecentFoods(limit)
        }.value

    override suspend fun lookupBarcode(code: String): FoodOut = api.lookupBarcode(code)

    override suspend fun createFood(req: FoodCreateRequest): FoodOut = api.createFood(req)

    override suspend fun estimatePhoto(image: ByteArray, mimeType: String): PhotoEstimateResponse =
        api.estimatePhoto(imagePart(image, mimeType))

    override suspend fun estimateLabel(image: ByteArray, mimeType: String): PhotoEstimateResponse =
        api.estimateLabel(imagePart(image, mimeType))

    override suspend fun parseVoice(text: String): PhotoEstimateResponse =
        api.parseVoice(VoiceParseRequest(text))

    private fun imagePart(image: ByteArray, mimeType: String): MultipartBody.Part {
        val body = image.toRequestBody(mimeType.toMediaTypeOrNull())
        // The server keys the upload on "image" (the multipart field name); the filename is cosmetic.
        return MultipartBody.Part.createFormData("image", "image.jpg", body)
    }
}
