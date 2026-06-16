package com.plate.ui.goals

import com.plate.data.remote.GoalOut
import com.plate.data.remote.GoalUpsertRequest
import com.plate.data.repository.GoalRepository
import com.plate.util.MainDispatcherRule
import com.plate.util.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private fun goalOut(type: String = "maintain") = GoalOut(
    id = "id-1",
    goalType = type,
    weightKg = 80.0,
    heightCm = 180.0,
    age = 30,
    sex = "male",
    activityLevel = "moderate",
    rateKgPerWeek = 0.0,
    createdAt = "2026-06-16T00:00:00Z",
)

private val REQUEST = GoalUpsertRequest("maintain", 80.0, 180.0, 30, "male", "moderate", 0.0)

private class FakeGoalRepository(
    private val existing: GoalOut? = null,
    private val failWith: Exception? = null,
) : GoalRepository {
    var savedRequest: GoalUpsertRequest? = null

    override suspend fun getGoal(): GoalOut {
        failWith?.let { throw it }
        return existing ?: throw retrofit2.HttpException(
            retrofit2.Response.error<GoalOut>(404, okhttp3.ResponseBody.create(null, ""))
        )
    }

    override suspend fun setGoal(request: GoalUpsertRequest): GoalOut {
        savedRequest = request
        return goalOut(request.goalType)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GoalViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `init loads existing goal into Success`() = runTest {
        val repo = FakeGoalRepository(existing = goalOut("cut"))
        val vm = GoalViewModel(repo)
        advanceUntilIdle()

        val state = vm.goal.value
        assertTrue(state is UiState.Success)
        assertEquals("cut", (state as UiState.Success<GoalOut?>).data?.goalType)
    }

    @Test
    fun `init with no goal emits Success with null`() = runTest {
        val repo = FakeGoalRepository(existing = null)
        val vm = GoalViewModel(repo)
        advanceUntilIdle()

        val state = vm.goal.value
        assertTrue(state is UiState.Success)
        assertNull((state as UiState.Success<GoalOut?>).data)
    }

    @Test
    fun `load failure emits Error`() = runTest {
        val repo = FakeGoalRepository(failWith = RuntimeException("network error"))
        val vm = GoalViewModel(repo)
        advanceUntilIdle()

        assertTrue(vm.goal.value is UiState.Error)
        assertEquals("network error", (vm.goal.value as UiState.Error).message)
    }

    @Test
    fun `save calls repository and emits Success`() = runTest {
        val repo = FakeGoalRepository(existing = null)
        val vm = GoalViewModel(repo)
        advanceUntilIdle()

        vm.save(REQUEST)
        advanceUntilIdle()

        assertEquals(REQUEST, repo.savedRequest)
        assertTrue(vm.saveState.value is UiState.Success)
    }

    @Test
    fun `clearSaveState resets back to Idle`() = runTest {
        val repo = FakeGoalRepository(existing = null)
        val vm = GoalViewModel(repo)
        advanceUntilIdle()

        vm.save(REQUEST)
        advanceUntilIdle()
        vm.clearSaveState()

        assertEquals(UiState.Idle, vm.saveState.value)
    }

    @Test
    fun `save failure emits Error on saveState`() = runTest {
        val repo = object : GoalRepository {
            override suspend fun getGoal(): GoalOut =
                throw retrofit2.HttpException(
                    retrofit2.Response.error<GoalOut>(404, okhttp3.ResponseBody.create(null, ""))
                )
            override suspend fun setGoal(request: GoalUpsertRequest): GoalOut =
                throw RuntimeException("save failed")
        }
        val vm = GoalViewModel(repo)
        advanceUntilIdle()

        vm.save(REQUEST)
        advanceUntilIdle()

        assertTrue(vm.saveState.value is UiState.Error)
        assertEquals("save failed", (vm.saveState.value as UiState.Error).message)
    }
}
