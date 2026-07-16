package com.plate

import android.app.Application
import com.plate.util.AppPreferences
import com.plate.util.NetworkSyncObserver
import com.plate.util.SuiteConfigReader
import com.plate.util.nudges.NudgeScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PlateApp : Application() {

    @Inject lateinit var networkSyncObserver: NetworkSyncObserver
    @Inject lateinit var suiteConfigReader: SuiteConfigReader
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var nudgeScheduler: NudgeScheduler

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Push any offline-queued diary writes as soon as connectivity returns.
        networkSyncObserver.register()
        // Adopt the server URL the Dragonfly hub manages, if it's installed and same-signed.
        suiteConfigReader.sync()
        // Retention nudges: keep the notification channel present, and re-arm the (inexact,
        // non-persistent) alarms on every launch when the user has opted in — self-heals after a
        // reboot / process death / the OS dropping an alarm.
        nudgeScheduler.ensureChannel()
        appScope.launch {
            if (appPreferences.nudgesEnabled.first()) nudgeScheduler.scheduleAll()
        }
    }
}
