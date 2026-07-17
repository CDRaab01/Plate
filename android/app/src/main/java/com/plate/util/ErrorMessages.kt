package com.plate.util

import java.io.IOException

/**
 * User-facing message for a caught failure. An [IOException] means the Plate server was
 * unreachable (offline, or the backend is down) — say that plainly instead of leaking transport
 * internals like "Unable to resolve host". Anything else (notably a `retrofit2.HttpException`
 * server rejection) keeps its own message, with [fallback] when there is none.
 */
fun Throwable.userMessage(fallback: String): String =
    if (this is IOException) "Can't reach the Plate server" else message ?: fallback
