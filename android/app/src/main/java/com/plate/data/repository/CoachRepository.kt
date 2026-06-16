package com.plate.data.repository

import com.plate.data.remote.ChatMessageDto

/** The AI coach: send the conversation so far, get the coach's next reply (CLAUDE.md §6). */
interface CoachRepository {
    suspend fun chat(messages: List<ChatMessageDto>): String
}
