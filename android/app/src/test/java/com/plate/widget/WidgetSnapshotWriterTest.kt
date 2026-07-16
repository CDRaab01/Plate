package com.plate.widget

import com.plate.data.remote.DailyLog
import com.plate.data.remote.MealGroup
import com.plate.data.remote.TotalsOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun day(
    consumed: TotalsOut,
    targets: TotalsOut,
) = DailyLog(
    date = "2026-07-15",
    meals = listOf(MealGroup("breakfast", emptyList(), consumed)),
    totals = consumed,
    targets = targets,
)

class WidgetSnapshotWriterTest {

    @Test
    fun `remaining is target minus consumed, rounded`() {
        val data = day(
            consumed = TotalsOut(800.4, 60.0, 90.0, 20.6),
            targets = TotalsOut(2000.0, 150.0, 200.0, 67.0),
        ).toWidgetData()
        assertEquals(1200, data.remainingKcal)
        assertEquals(90, data.remainingProteinG)
        assertEquals(110, data.remainingCarbsG)
        assertEquals(46, data.remainingFatG) // 67 - 20.6 = 46.4 -> 46
        assertFalse(data.overTarget)
    }

    @Test
    fun `over target flags when consumed meets or exceeds the calorie goal`() {
        val data = day(
            consumed = TotalsOut(2100.0, 160.0, 210.0, 70.0),
            targets = TotalsOut(2000.0, 150.0, 200.0, 67.0),
        ).toWidgetData()
        assertEquals(-100, data.remainingKcal)
        assertTrue(data.overTarget)
    }

    @Test
    fun `a zero calorie target never reads as over`() {
        val data = day(
            consumed = TotalsOut(500.0, 10.0, 10.0, 10.0),
            targets = TotalsOut(0.0, 0.0, 0.0, 0.0),
        ).toWidgetData()
        assertFalse(data.overTarget)
    }
}
