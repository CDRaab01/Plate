package com.plate.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import retrofit2.HttpException
import java.io.IOException

/**
 * User-facing message for a caught failure. An [IOException] means the Plate server was
 * unreachable (offline, or the backend is down) — say that plainly instead of leaking transport
 * internals like "Unable to resolve host". A `retrofit2.HttpException` carries the server's own
 * explanation in its FastAPI `{"detail": "..."}` body — surface that (e.g. "Couldn't read a menu
 * from that link — build the restaurant by hand") rather than the bare "HTTP 422". Anything else
 * keeps its own message, with [fallback] when there is none.
 */
fun Throwable.userMessage(fallback: String): String = when (this) {
    is IOException -> "Can't reach the Plate server"
    is HttpException -> apiDetail() ?: fallback
    else -> message ?: fallback
}

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * The human-readable `detail` string FastAPI returns on an error, or null. Request-validation
 * errors use a `detail` *list* rather than a string; those aren't user-friendly, so we skip them
 * (not a [JsonPrimitive]) and let the caller's fallback speak. The error body reads once — fine,
 * this is the only reader.
 */
private fun HttpException.apiDetail(): String? = try {
    val body = response()?.errorBody()?.string()?.takeIf { it.isNotBlank() }
    val detail = body?.let { errorJson.parseToJsonElement(it).jsonObject["detail"] }
    (detail as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}
