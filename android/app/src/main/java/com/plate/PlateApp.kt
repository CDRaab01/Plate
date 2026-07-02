package com.plate

import android.app.Application
import com.plate.util.NetworkSyncObserver
import com.plate.util.SuiteConfigReader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PlateApp : Application() {

    @Inject lateinit var networkSyncObserver: NetworkSyncObserver
    @Inject lateinit var suiteConfigReader: SuiteConfigReader

    override fun onCreate() {
        super.onCreate()
        // Push any offline-queued diary writes as soon as connectivity returns.
        networkSyncObserver.register()
        // Adopt the server URL the Dragonfly hub manages, if it's installed and same-signed.
        suiteConfigReader.sync()
    }
}
