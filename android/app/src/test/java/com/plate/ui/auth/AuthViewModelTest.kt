package com.plate.ui.auth

import com.plate.data.remote.SuiteAuthManager
import com.plate.data.repository.AuthRepository
import com.plate.util.AuthEventBus
import com.plate.util.MainDispatcherRule
import com.plate.util.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock

private class FakeAuthRepository(
    private val failWith: Exception? = null,
) : AuthRepository {
    var loggedIn = false
    var registered = false

    override suspend fun register(name: String, email: String, password: String, inviteCode: String?) {
        failWith?.let { throw it }
        registered = true
    }

    override suspend fun login(email: String, password: String) {
        failWith?.let { throw it }
        loggedIn = true
    }

    override suspend fun forgotPassword(email: String) {
        failWith?.let { throw it }
    }

    override suspend fun resetPassword(token: String, newPassword: String) {
        failWith?.let { throw it }
    }

    override suspend fun logout() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `login success emits Success`() = runTest {
        val repo = FakeAuthRepository()
        val vm = AuthViewModel(repo, mock<SuiteAuthManager>(), AuthEventBus())

        vm.login("a@b.com", "password1")
        advanceUntilIdle()

        assertTrue(repo.loggedIn)
        assertEquals(UiState.Success(Unit), vm.authState.value)
    }

    @Test
    fun `login failure emits Error with message`() = runTest {
        val repo = FakeAuthRepository(failWith = RuntimeException("Invalid credentials"))
        val vm = AuthViewModel(repo, mock<SuiteAuthManager>(), AuthEventBus())

        vm.login("a@b.com", "wrong")
        advanceUntilIdle()

        val state = vm.authState.value
        assertTrue(state is UiState.Error)
        assertEquals("Invalid credentials", (state as UiState.Error).message)
    }

    @Test
    fun `register success emits Success`() = runTest {
        val repo = FakeAuthRepository()
        val vm = AuthViewModel(repo, mock<SuiteAuthManager>(), AuthEventBus())

        vm.register("Casey", "a@b.com", "password1")
        advanceUntilIdle()

        assertTrue(repo.registered)
        assertEquals(UiState.Success(Unit), vm.authState.value)
    }
}
