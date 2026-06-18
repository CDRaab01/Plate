package com.plate

import android.app.Application
import com.plate.util.NetworkSyncObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PlateApp : Application() {

    @Inject lateinit var networkSyncObserver: NetworkSyncObserver

    override fun onCreate() {
        super.onCreate()
        // Push any offline-queued diary writes as soon as connectivity returns.
        networkSyncObserver.register()
    }
}
