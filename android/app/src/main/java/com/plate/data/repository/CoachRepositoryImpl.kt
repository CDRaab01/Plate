package com.plate.data.repository

import com.plate.data.remote.ApiService
import com.plate.data.remote.ChatMessageDto
import com.plate.data.remote.ChatRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoachRepositoryImpl @Inject constructor(
    private val api: ApiService,
) : CoachRepository {

    override suspend fun chat(messages: List<ChatMessageDto>): String =
        api.coachChat(ChatRequest(messages)).reply
}
