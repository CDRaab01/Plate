package com.plate.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.data.remote.ChatMessageDto
import com.plate.data.repository.CoachRepository
import com.plate.util.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One turn in the coach conversation, as shown in the chat transcript. */
data class CoachMessage(val role: String, val content: String) {
    val isUser: Boolean get() = role == ROLE_USER

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/** Chat transcript + send/await state for the coach screen. */
data class CoachUiState(
    val messages: List<CoachMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
)

/**
 * Drives the AI coach chat (CLAUDE.md §6). Keeps the running transcript, sends the whole
 * conversation to the backend (which injects the system prompt + the user's remaining macros), and
 * appends the reply. The coach's numbers are estimates the user confirms — surfaced as plain chat,
 * never auto-logged.
 */
@HiltViewModel
class CoachViewModel @Inject constructor(
    private val coachRepository: CoachRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CoachUiState())
    val state: StateFlow<CoachUiState> = _state

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return

        val withUser = _state.value.messages + CoachMessage(CoachMessage.ROLE_USER, trimmed)
        _state.update { it.copy(messages = withUser, sending = true, error = null) }

        viewModelScope.launch {
            try {
                val reply = coachRepository.chat(withUser.map { ChatMessageDto(it.role, it.content) })
                _state.update {
                    it.copy(
                        messages = it.messages + CoachMessage(CoachMessage.ROLE_ASSISTANT, reply),
                        sending = false,
                    )
                }
            } catch (e: Exception) {
                // Keep the user's message in the transcript so they can retry without retyping.
                _state.update {
                    it.copy(sending = false, error = e.userMessage("The coach is unavailable right now"))
                }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
