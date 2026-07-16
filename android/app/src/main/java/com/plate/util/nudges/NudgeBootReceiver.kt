package com.plate.util.nudges

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.plate.util.AppPreferences

/**
 * Alarms don't survive a reboot, so re-arm the nudges on boot when the feature is enabled. Uses an
 * [EntryPoint] since receivers can't be Hilt-injected.
 */
class NudgeBootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootEntryPoint {
        fun appPreferences(): AppPreferences
        fun nudgeScheduler(): NudgeScheduler
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BootEntryPoint::class.java,
        )
        val pending = goAsync()
        try {
            val enabled = runBlocking { entryPoint.appPreferences().nudgesEnabled.first() }
            if (enabled) entryPoint.nudgeScheduler().scheduleAll()
        } finally {
            pending.finish()
        }
    }
}
