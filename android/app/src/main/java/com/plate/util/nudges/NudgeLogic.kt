package com.plate.util.nudges

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure scheduling logic for the retention nudges (Tier W2b), kept free of Android framework types so
 * it is exhaustively unit-testable. The receiver/scheduler are thin wrappers around this.
 *
 * Three gentle, non-nagging daily nudges — two meal reminders and one evening "nothing logged today"
 * check — all opt-in and quiet-hours-respecting, all client-side (no server).
 */
enum class NudgeKind(
    /** Stable AlarmManager request code + notification id per nudge (must not collide). */
    val requestCode: Int,
    val hour: Int,
    val minute: Int,
) {
    /** "Time to log lunch?" around midday. */
    LUNCH(requestCode = 4101, hour = 12, minute = 30),

    /** "Time to log dinner?" in the early evening. */
    DINNER(requestCode = 4102, hour = 18, minute = 30),

    /** Fires only if nothing was logged today — an "anything to log?" evening reminder. */
    EVENING_CHECK(requestCode = 4103, hour = 20, minute = 0),
}

object NudgeLogic {

    /**
     * Whether [hour] (0–23) falls inside the quiet window [quietStart, quietEnd). The window wraps
     * past midnight when start > end (e.g. 22→7 covers 22,23,0..6). A degenerate start==end window is
     * treated as "no quiet hours" so a mis-set pair can't silence every nudge forever.
     */
    fun isQuietHour(hour: Int, quietStart: Int, quietEnd: Int): Boolean {
        if (quietStart == quietEnd) return false
        return if (quietStart < quietEnd) {
            hour in quietStart until quietEnd
        } else {
            hour >= quietStart || hour < quietEnd
        }
    }

    /**
     * Epoch-day (days since 1970-01-01 in [zone]) for [instantMillis] — the granularity the
     * "logged today?" check compares on.
     */
    fun epochDay(instantMillis: Long, zone: ZoneId): Long =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(instantMillis), zone).toLocalDate().toEpochDay()

    /**
     * The next wall-clock time [kind] should fire, as epoch millis: today at its time if that is still
     * strictly in the future, otherwise the same time tomorrow. [nowMillis] and [zone] make it
     * deterministic in tests.
     */
    fun nextTriggerMillis(kind: NudgeKind, nowMillis: Long, zone: ZoneId): Long {
        val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone)
        val todayAt = LocalDateTime.of(now.toLocalDate(), LocalTime.of(kind.hour, kind.minute))
        val target = if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
        return target.atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * Whether the evening "nothing logged today" nudge should actually be posted: only when the last
     * logged day is not today. [today] and [lastLogEpochDay] are local epoch-days.
     */
    fun shouldPostEveningCheck(lastLogEpochDay: Long, today: Long): Boolean =
        lastLogEpochDay != today

    /** Convenience: today's local epoch-day. */
    fun todayEpochDay(nowMillis: Long, zone: ZoneId): Long = epochDay(nowMillis, zone)

    // Kept for symmetry with LocalDate consumers/tests.
    fun localDate(instantMillis: Long, zone: ZoneId): LocalDate =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(instantMillis), zone).toLocalDate()
}
