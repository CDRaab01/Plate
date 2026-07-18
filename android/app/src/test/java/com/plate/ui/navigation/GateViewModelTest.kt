package com.plate.ui.navigation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.plate.data.local.TokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The startup gate resolves purely from the persisted token — no network — so a cached session
 * opens the app past login even with the backend unreachable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GateViewModelTest {

    private val tokenStore = TokenStore(ApplicationProvider.getApplicationContext())

    @Before
    fun resetStore() {
        // The DataStore instance is process-wide (the preferencesDataStore delegate caches it),
        // so clear between tests to keep them order-independent.
        runBlocking { tokenStore.clear() }
    }

    @Test
    fun `no cached token resolves signed out`() = runTest {
        assertFalse(GateViewModel(tokenStore).isSignedIn())
    }

    @Test
    fun `a cached token resolves signed in without any network call`() = runTest {
        tokenStore.save("access-token", "refresh-token")
        assertTrue(GateViewModel(tokenStore).isSignedIn())
    }
}
