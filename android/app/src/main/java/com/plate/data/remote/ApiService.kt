package com.plate.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** Retrofit contract for the Plate backend. Phase 1 covers accounts; more lands in later phases. */
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
}
