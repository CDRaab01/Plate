package com.plate.util.nudges

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the retention nudges via [AlarmManager]. Deliberately uses **inexact**
 * (`setAndAllowWhileIdle`) alarms so no `SCHEDULE_EXACT_ALARM` permission is needed and the reminders
 * stay gentle rather than to-the-second. Each alarm re-schedules itself for the next day when it
 * fires (see [NudgeReceiver]); this class (re)arms all of them on enable, app start, and boot.
 */
@Singleton
class NudgeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val CHANNEL_ID = "plate_nudges"
        const val EXTRA_KIND = "nudge_kind"
        private const val CHANNEL_NAME = "Reminders"
        private const val CHANNEL_DESC = "Gentle meal-logging reminders"
    }

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** Create the (low-importance) notification channel once; idempotent. */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = CHANNEL_DESC
            }
            manager.createNotificationChannel(channel)
        }
    }

    /** Arm every nudge for its next occurrence. No-op-safe to call repeatedly (replaces existing). */
    fun scheduleAll(nowMillis: Long = System.currentTimeMillis()) {
        ensureChannel()
        val am = alarmManager ?: return
        val zone = java.time.ZoneId.systemDefault()
        for (kind in NudgeKind.entries) {
            val triggerAt = NudgeLogic.nextTriggerMillis(kind, nowMillis, zone)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(kind))
        }
    }

    /** Re-arm a single nudge for its next occurrence (called by the receiver after it fires). */
    fun scheduleNext(kind: NudgeKind, nowMillis: Long = System.currentTimeMillis()) {
        val am = alarmManager ?: return
        val zone = java.time.ZoneId.systemDefault()
        val triggerAt = NudgeLogic.nextTriggerMillis(kind, nowMillis, zone)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(kind))
    }

    /** Cancel all scheduled nudges (called when the user turns the feature off). */
    fun cancelAll() {
        val am = alarmManager ?: return
        for (kind in NudgeKind.entries) am.cancel(pendingIntent(kind))
    }

    private fun pendingIntent(kind: NudgeKind): PendingIntent {
        val intent = Intent(context, NudgeReceiver::class.java).apply {
            action = "com.plate.NUDGE"
            putExtra(EXTRA_KIND, kind.name)
        }
        return PendingIntent.getBroadcast(
            context,
            kind.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
