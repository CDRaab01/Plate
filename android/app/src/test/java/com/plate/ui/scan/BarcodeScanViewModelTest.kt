package com.plate.ui.scan

import com.plate.data.remote.FoodCreateRequest
import com.plate.data.remote.FoodDetailOut
import com.plate.data.remote.FoodOut
import com.plate.data.remote.PhotoEstimateResponse
import com.plate.data.repository.FoodRepository
import com.plate.util.AppPreferences
import com.plate.util.MainDispatcherRule
import com.plate.util.UiState
import com.plate.util.UnitSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response

private fun food(id: String = "1", name: String = "Oat Milk") = FoodDetailOut(
    id = id,
    source = "off",
    name = name,
    barcode = "222",
    kcalPer100g = 46.0,
    proteinGPer100g = 1.0,
    carbsGPer100g = 6.7,
    fatGPer100g = 1.5,
)

private fun httpException(code: Int) = HttpException(
    Response.error<Any>(code, "".toResponseBody("application/json".toMediaType())),
)

private class FakeFoodRepository(
    private val result: FoodDetailOut? = null,
    private val failWith: Exception? = null,
) : FoodRepository {
    var lookupCount = 0
    var lastCode: String? = null

    override suspend fun recentFoods(limit: Int) = emptyList<com.plate.data.remote.RecentFoodOut>()

    override suspend fun search(query: String, filter: String?): List<FoodOut> = emptyList()

    override suspend fun getFood(id: String): FoodDetailOut =
        throw IllegalStateException("not used")

    override suspend fun lookupBarcode(code: String): FoodDetailOut {
        lookupCount++
        lastCode = code
        failWith?.let { throw it }
        return result ?: throw IllegalStateException("no result")
    }

    override suspend fun createFood(req: FoodCreateRequest): FoodOut =
        throw IllegalStateException("not used")

    override suspend fun estimatePhoto(image: ByteArray, mimeType: String): PhotoEstimateResponse =
        throw IllegalStateException("not used")

    override suspend fun estimateLabel(image: ByteArray, mimeType: String): PhotoEstimateResponse =
        throw IllegalStateException("not used")

    override suspend fun parseVoice(text: String): PhotoEstimateResponse =
        throw IllegalStateException("not used")
}

@OptIn(ExperimentalCoroutinesApi::class)
class BarcodeScanViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private fun prefs(): AppPreferences =
        mock { whenever(it.unitSystem).thenReturn(flowOf(UnitSystem.IMPERIAL)) }

    @Test
    fun `scanned barcode resolves to Success`() = runTest {
        val repo = FakeFoodRepository(result = food())
        val vm = BarcodeScanViewModel(repo, prefs())

        vm.onBarcodeScanned(" 222 ")
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is UiState.Success)
        assertEquals(food(), (state as UiState.Success).data)
        assertEquals("222", repo.lastCode) // trimmed before lookup
    }

    @Test
    fun `404 surfaces a friendly not-found error`() = runTest {
        val repo = FakeFoodRepository(failWith = httpException(404))
        val vm = BarcodeScanViewModel(repo, prefs())

        vm.onBarcodeScanned("000")
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is UiState.Error)
        assertEquals("No product found for that barcode", (state as UiState.Error).message)
    }

    @Test
    fun `repeated scans are ignored once a lookup succeeds`() = runTest {
        val repo = FakeFoodRepository(result = food())
        val vm = BarcodeScanViewModel(repo, prefs())

        vm.onBarcodeScanned("222")
        advanceUntilIdle()
        vm.onBarcodeScanned("222")
        vm.onBarcodeScanned("333")
        advanceUntilIdle()

        // The camera streams continuously; only the first clean read should hit the repository.
        assertEquals(1, repo.lookupCount)
    }

    @Test
    fun `blank scan is ignored`() = runTest {
        val repo = FakeFoodRepository(result = food())
        val vm = BarcodeScanViewModel(repo, prefs())

        vm.onBarcodeScanned("   ")
        advanceUntilIdle()

        assertEquals(UiState.Idle, vm.state.value)
        assertEquals(0, repo.lookupCount)
    }

    @Test
    fun `reset returns to Idle so the user can scan again`() = runTest {
        val repo = FakeFoodRepository(failWith = httpException(500))
        val vm = BarcodeScanViewModel(repo, prefs())

        vm.onBarcodeScanned("222")
        advanceUntilIdle()
        assertTrue(vm.state.value is UiState.Error)

        vm.reset()
        assertEquals(UiState.Idle, vm.state.value)
    }
}
