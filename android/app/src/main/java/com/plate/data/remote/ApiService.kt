package com.plate.data.remote

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
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

    // Trade a Dragonfly suite token for a Plate session (BROKER.md Phase 2c).
    @POST("auth/suite")
    suspend fun suiteLogin(@Body body: SuiteLoginRequest): TokenResponse

    @POST("auth/reset-password")
    suspend fun resetPassword(@Body body: ResetPasswordRequest)

    @GET("users/me")
    suspend fun me(): UserOut

    /** Update preferences (currently the lb/kg unit system). */
    @PATCH("users/me/settings")
    suspend fun updateSettings(@Body body: UserSettingsUpdate): UserOut

    // ── Phase 2: food search + manual logging ────────────────────────────────

    @GET("foods/search")
    suspend fun searchFoods(@Query("q") query: String): List<FoodOut>

    /** Phase 4: resolve a scanned barcode (local cache → Open Food Facts). 404 if unknown. */
    @GET("foods/barcode/{code}")
    suspend fun lookupBarcode(@Path("code") code: String): FoodOut

    /** Create a user-defined custom food (used to persist a confirmed photo estimate). */
    @POST("foods")
    suspend fun createFood(@Body body: FoodCreateRequest): FoodOut

    /** Phase 6: estimate the foods + macros in a meal photo. Returns an editable, never-logged draft. */
    @Multipart
    @POST("foods/photo")
    suspend fun estimatePhoto(@Part image: MultipartBody.Part): PhotoEstimateResponse

    @POST("log")
    suspend fun createLogEntry(@Body body: LogEntryCreate): LogEntryOut

    @GET("log")
    suspend fun getDay(@Query("date") date: String? = null): DailyLog

    @PUT("log/{id}")
    suspend fun updateLogEntry(@Path("id") id: String, @Body body: LogEntryUpdate): LogEntryOut

    @DELETE("log/{id}")
    suspend fun deleteLogEntry(@Path("id") id: String)

    // ── Phase 8: quick-add, recipes, weekly summary ──────────────────────────

    /** Quick add: log raw kcal/macros directly, with no source food. */
    @POST("log/quick-add")
    suspend fun quickAdd(@Body body: QuickAddRequest): LogEntryOut

    /** Per-day totals + period total/averages. Defaults server-side to the last 7 days. */
    @GET("log/summary")
    suspend fun getSummary(
        @Query("start") start: String? = null,
        @Query("end") end: String? = null,
    ): RangeSummary

    /** Search external recipes (Spoonacular). 503 until the server has a key configured. */
    @GET("recipes/discover")
    suspend fun discoverRecipes(@Query("q") query: String): List<DiscoveredRecipe>

    /** Import an external recipe (by provider id) as a saved recipe. */
    @POST("recipes/import")
    suspend fun importRecipe(@Body body: RecipeImportRequest): RecipeOut

    @GET("recipes")
    suspend fun getRecipes(): List<RecipeOut>

    @GET("recipes/{id}")
    suspend fun getRecipe(@Path("id") id: String): RecipeOut

    @POST("recipes")
    suspend fun createRecipe(@Body body: RecipeCreate): RecipeOut

    @PATCH("recipes/{id}")
    suspend fun updateRecipe(@Path("id") id: String, @Body body: RecipeUpdate): RecipeOut

    @PUT("recipes/{id}/items")
    suspend fun replaceRecipeItems(
        @Path("id") id: String,
        @Body body: RecipeItemsReplace,
    ): RecipeOut

    @DELETE("recipes/{id}")
    suspend fun deleteRecipe(@Path("id") id: String)

    /** Expand a saved recipe into a day's meal (one log entry per item). */
    @POST("recipes/{id}/log")
    suspend fun logRecipe(@Path("id") id: String, @Body body: RecipeLogRequest): List<LogEntryOut>


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

    // ── Bodyweight metrics + trend (Home dashboard / adaptive targets) ────────

    @GET("metrics/weight")
    suspend fun getWeightMetrics(): List<BodyMetricOut>

    @POST("metrics/weight")
    suspend fun addWeightMetric(@Body body: BodyMetricCreate): BodyMetricOut

    @GET("metrics/weight/trend")
    suspend fun getWeightTrend(): WeightTrendOut
}
