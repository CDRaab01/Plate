package com.plate.ui.coach

import com.plate.data.remote.ChatMessageDto
import com.plate.data.repository.CoachRepository
import com.plate.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeCoachRepository(
    private val reply: String = "Sure!",
    private val failWith: Exception? = null,
) : CoachRepository {
    var calls = 0
    var lastMessages: List<ChatMessageDto>? = null

    override suspend fun chat(messages: List<ChatMessageDto>): String {
        calls++
        lastMessages = messages
        failWith?.let { throw it }
        return reply
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CoachViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `sending appends user message then assistant reply`() = runTest {
        val repo = FakeCoachRepository(reply = "Have some oats.")
        val vm = CoachViewModel(repo)

        vm.sendMessage("what's for breakfast?")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(2, state.messages.size)
        assertEquals(CoachMessage.ROLE_USER, state.messages[0].role)
        assertEquals("what's for breakfast?", state.messages[0].content)
        assertEquals(CoachMessage.ROLE_ASSISTANT, state.messages[1].role)
        assertEquals("Have some oats.", state.messages[1].content)
        assertFalse(state.sending)
        assertNull(state.error)
    }

    @Test
    fun `whole transcript is sent to the repository`() = runTest {
        val repo = FakeCoachRepository(reply = "ok")
        val vm = CoachViewModel(repo)

        vm.sendMessage("first")
        advanceUntilIdle()
        vm.sendMessage("second")
        advanceUntilIdle()

        // Second call carries: user "first", assistant "ok", user "second".
        val sent = repo.lastMessages!!
        assertEquals(3, sent.size)
        assertEquals("first", sent[0].content)
        assertEquals("second", sent[2].content)
    }

    @Test
    fun `blank input is ignored`() = runTest {
        val repo = FakeCoachRepository()
        val vm = CoachViewModel(repo)

        vm.sendMessage("   ")
        advanceUntilIdle()

        assertEquals(0, repo.calls)
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun `failure surfaces error and keeps the user message`() = runTest {
        val repo = FakeCoachRepository(failWith = RuntimeException("LM Studio is not reachable"))
        val vm = CoachViewModel(repo)

        vm.sendMessage("hi")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("LM Studio is not reachable", state.error)
        assertFalse(state.sending)
        // The user's turn stays so they can retry without retyping; no assistant turn was added.
        assertEquals(1, state.messages.size)
        assertEquals(CoachMessage.ROLE_USER, state.messages[0].role)
    }

    @Test
    fun `clearError resets the error`() = runTest {
        val repo = FakeCoachRepository(failWith = RuntimeException("boom"))
        val vm = CoachViewModel(repo)

        vm.sendMessage("hi")
        advanceUntilIdle()
        assertEquals("boom", vm.state.value.error)

        vm.clearError()
        assertNull(vm.state.value.error)
    }
}
