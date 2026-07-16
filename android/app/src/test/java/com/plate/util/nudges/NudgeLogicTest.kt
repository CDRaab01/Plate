package com.plate.util.nudges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class NudgeLogicTest {

    private val utc = ZoneId.of("UTC")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

    // ── Quiet hours (with midnight wraparound) ───────────────────────────────

    @Test
    fun `quiet hours wrap past midnight`() {
        // Default 22:00 → 07:00 window.
        assertTrue(NudgeLogic.isQuietHour(22, 22, 7))
        assertTrue(NudgeLogic.isQuietHour(23, 22, 7))
        assertTrue(NudgeLogic.isQuietHour(0, 22, 7))
        assertTrue(NudgeLogic.isQuietHour(6, 22, 7))
        assertFalse(NudgeLogic.isQuietHour(7, 22, 7))
        assertFalse(NudgeLogic.isQuietHour(12, 22, 7))
        assertFalse(NudgeLogic.isQuietHour(21, 22, 7))
    }

    @Test
    fun `quiet hours within a single day`() {
        assertTrue(NudgeLogic.isQuietHour(2, 1, 5))
        assertFalse(NudgeLogic.isQuietHour(5, 1, 5))
        assertFalse(NudgeLogic.isQuietHour(0, 1, 5))
    }

    @Test
    fun `equal start and end means no quiet hours`() {
        for (h in 0..23) assertFalse(NudgeLogic.isQuietHour(h, 9, 9))
    }

    // ── Next trigger time ────────────────────────────────────────────────────

    @Test
    fun `next trigger is later today when the time has not passed`() {
        val now = millis(2026, 7, 16, 9, 0) // 09:00
        val trigger = NudgeLogic.nextTriggerMillis(NudgeKind.LUNCH, now, utc)
        assertEquals(millis(2026, 7, 16, 12, 30), trigger) // 12:30 today
    }

    @Test
    fun `next trigger rolls to tomorrow when the time has passed`() {
        val now = millis(2026, 7, 16, 19, 0) // 19:00, after lunch & dinner
        val lunch = NudgeLogic.nextTriggerMillis(NudgeKind.LUNCH, now, utc)
        assertEquals(millis(2026, 7, 17, 12, 30), lunch) // 12:30 tomorrow
        val dinner = NudgeLogic.nextTriggerMillis(NudgeKind.DINNER, now, utc)
        assertEquals(millis(2026, 7, 17, 18, 30), dinner)
    }

    @Test
    fun `exact-time-now rolls forward (strictly future)`() {
        val now = millis(2026, 7, 16, 12, 30)
        val trigger = NudgeLogic.nextTriggerMillis(NudgeKind.LUNCH, now, utc)
        assertEquals(millis(2026, 7, 17, 12, 30), trigger)
    }

    // ── Evening "nothing logged" gate ────────────────────────────────────────

    @Test
    fun `evening check posts only when nothing logged today`() {
        val today = 20_300L
        assertTrue(NudgeLogic.shouldPostEveningCheck(lastLogEpochDay = 20_299L, today = today))
        assertTrue(NudgeLogic.shouldPostEveningCheck(lastLogEpochDay = 0L, today = today))
        assertFalse(NudgeLogic.shouldPostEveningCheck(lastLogEpochDay = today, today = today))
    }

    @Test
    fun `epochDay is stable within a day and increments across midnight`() {
        val a = NudgeLogic.epochDay(millis(2026, 7, 16, 0, 1), utc)
        val b = NudgeLogic.epochDay(millis(2026, 7, 16, 23, 59), utc)
        val c = NudgeLogic.epochDay(millis(2026, 7, 17, 0, 1), utc)
        assertEquals(a, b)
        assertEquals(a + 1, c)
    }

    @Test
    fun `request codes are unique per kind`() {
        val codes = NudgeKind.entries.map { it.requestCode }
        assertEquals(codes.size, codes.toSet().size)
    }
}
