package com.plate.util.nudges

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.plate.MainActivity
import com.plate.R
import com.plate.util.AppPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.ZoneId

/**
 * Fires a retention nudge and re-arms it for tomorrow. Broadcast receivers can't be Hilt-injected, so
 * dependencies come via an [EntryPoint] (mirrors the widget). All gating is honored here — the
 * feature must be enabled, the notifications permission granted, and the current hour outside quiet
 * hours; the evening nudge additionally posts only when nothing was logged today. Whatever the
 * outcome, the same nudge is always re-scheduled so the daily cadence continues.
 */
class NudgeReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NudgeEntryPoint {
        fun appPreferences(): AppPreferences
        fun nudgeScheduler(): NudgeScheduler
    }

    override fun onReceive(context: Context, intent: Intent) {
        val kind = runCatching { NudgeKind.valueOf(intent.getStringExtra(NudgeScheduler.EXTRA_KIND) ?: "") }
            .getOrNull() ?: return

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            NudgeEntryPoint::class.java,
        )
        val prefs = entryPoint.appPreferences()
        val scheduler = entryPoint.nudgeScheduler()

        // goAsync so the short DataStore reads complete before the process is reclaimed.
        val pending = goAsync()
        try {
            val enabled = runBlocking { prefs.nudgesEnabled.first() }
            if (enabled && shouldPost(context, prefs, kind)) {
                postNotification(context, kind)
            }
        } finally {
            // Always re-arm tomorrow's occurrence, even when this one was suppressed.
            scheduler.scheduleNext(kind)
            pending.finish()
        }
    }

    private fun shouldPost(context: Context, prefs: AppPreferences, kind: NudgeKind): Boolean {
        if (!hasNotificationPermission(context)) return false

        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val hour = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone).hour
        val quietStart = runBlocking { prefs.quietStartHour.first() }
        val quietEnd = runBlocking { prefs.quietEndHour.first() }
        if (NudgeLogic.isQuietHour(hour, quietStart, quietEnd)) return false

        if (kind == NudgeKind.EVENING_CHECK) {
            val lastLog = runBlocking { prefs.lastLogEpochDay.first() }
            val today = NudgeLogic.todayEpochDay(now, zone)
            if (!NudgeLogic.shouldPostEveningCheck(lastLog, today)) return false
        }
        return true
    }

    private fun postNotification(context: Context, kind: NudgeKind) {
        if (!hasNotificationPermission(context)) return
        val (title, text) = messageFor(kind)
        val tapIntent = PendingIntent.getActivity(
            context,
            kind.requestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NudgeScheduler.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(kind.requestCode, notification)
    }

    private fun messageFor(kind: NudgeKind): Pair<String, String> = when (kind) {
        NudgeKind.LUNCH -> "Time to log lunch?" to "Tap to add what you ate to your diary."
        NudgeKind.DINNER -> "Time to log dinner?" to "Tap to add what you ate to your diary."
        NudgeKind.EVENING_CHECK ->
            "Nothing logged today" to "Add today's meals before the day wraps up."
    }

    private fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
}
