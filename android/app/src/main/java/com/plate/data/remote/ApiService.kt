package com.plate.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Retrofit contract for the Plate backend. Phase 1 covers accounts; Phase 2 adds food + log. */
interface ApiService {

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): TokenResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): TokenResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenResponse

    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequest)

    @POST("auth/reset-password")
    suspend fun resetPassword(@Body body: ResetPasswordRequest)

    @GET("users/me")
    suspend fun me(): UserOut

    // ── Phase 2: food search + manual logging ────────────────────────────────

    @GET("foods/search")
    suspend fun searchFoods(@Query("q") query: String): List<FoodOut>

    /** Phase 4: resolve a scanned barcode (local cache → Open Food Facts). 404 if unknown. */
    @GET("foods/barcode/{code}")
    suspend fun lookupBarcode(@Path("code") code: String): FoodOut

    @POST("log")
    suspend fun createLogEntry(@Body body: LogEntryCreate): LogEntryOut

    @GET("log")
    suspend fun getDay(@Query("date") date: String? = null): DailyLog

    @PUT("log/{id}")
    suspend fun updateLogEntry(@Path("id") id: String, @Body body: LogEntryUpdate): LogEntryOut

    @DELETE("log/{id}")
    suspend fun deleteLogEntry(@Path("id") id: String)

    // ── Phase 3: goals + computed targets ────────────────────────────────────

    @PUT("goals")
    suspend fun setGoal(@Body body: GoalUpsertRequest): GoalOut

    @GET("goals")
    suspend fun getGoal(): GoalOut

    @GET("goals/targets")
    suspend fun getTargets(@Query("date") date: String? = null): TargetsOut

    // ── Phase 5: AI coach chat ───────────────────────────────────────────────

    @POST("ai/chat")
    suspend fun coachChat(@Body body: ChatRequest): ChatResponse
}
