package com.plate.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.plate.data.repository.LogRepository
import com.plate.data.repository.MetricRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers a ConnectivityManager callback and pushes any offline-queued writes (diary quick-adds
 * + weigh-ins) whenever the device re-gains internet access — so anything logged offline reaches
 * the server as soon as connectivity returns, without the user reopening the app's screens.
 * Mirrors Spotter's NetworkSyncObserver.
 */
@Singleton
class NetworkSyncObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logRepository: LogRepository,
    private val metricRepository: MetricRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun register() {
        // Reconnect-sync is a convenience (getDay also flushes pending), so a failure here must
        // never crash app startup — e.g. a missing permission or an OEM that restricts the API.
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        // Each drain is independently best-effort — one failing must not block
                        // the other.
                        runCatching { logRepository.syncPending() }
                        runCatching { metricRepository.sync() }
                    }
                }
            })
        } catch (e: Exception) {
            Log.w("NetworkSyncObserver", "Could not register network callback: ${e.message}")
        }
    }
}
