package com.plate.data.remote

import kotlinx.serialization.Serializable

/**
 * AI coach chat DTOs (CLAUDE.md §6). Mirrors the backend contract: the full conversation goes up as
 * a list of role/content turns, a single assistant reply comes back. The macro context the coach
 * reasons over is derived server-side, so it isn't part of the request.
 */
@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String,
)

@Serializable
data class ChatRequest(
    val messages: List<ChatMessageDto>,
)

@Serializable
data class ChatResponse(
    val reply: String,
)
