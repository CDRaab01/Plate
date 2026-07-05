package com.plate.ui.home

import com.plate.data.remote.AdaptiveTdeeOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure DTO→display mapping for the Home adaptive-maintenance card (ROADMAP2 T3 #1). */
class AdaptiveDisplayTest {

    private fun dto(
        status: String,
        corrected: Double = 2600.0,
        adjustment: Double = 0.0,
        nLogged: Int = 0,
    ) = AdaptiveTdeeOut(
        date = "2026-07-04",
        status = status,
        formulaTdee = 2600.0,
        correctedTdee = corrected,
        observedMaintenance = if (status == "active") corrected else null,
        adjustmentKcal = adjustment,
        confidence = 1.0,
        nLoggedDays = nLogged,
        windowDays = 14,
        minLoggedDays = 10,
    )

    @Test
    fun active_shows_corrected_hero_and_signed_adjustment() {
        val up = adaptiveDisplay(dto("active", corrected = 2750.0, adjustment = 150.0, nLogged = 14))
        assertEquals("2750 kcal", up.heroKcal)
        assertTrue(up.title.contains("+150 kcal"))
        assertTrue(up.title.contains("raised"))
        assertTrue(up.caption.contains("14 of 14"))

        val down = adaptiveDisplay(dto("active", corrected = 2400.0, adjustment = -200.0, nLogged = 12))
        assertEquals("2400 kcal", down.heroKcal)
        assertTrue(down.title.contains("-200 kcal"))
        assertTrue(down.title.contains("trimmed"))
    }

    @Test
    fun learning_shows_progress_no_hero() {
        val d = adaptiveDisplay(dto("learning", nLogged = 6))
        assertNull(d.heroKcal)
        assertTrue(d.caption.contains("6 of 10"))
    }

    @Test
    fun insufficient_shows_locked_no_hero() {
        val d = adaptiveDisplay(dto("insufficient_data"))
        assertNull(d.heroKcal)
        assertTrue(d.title.contains("locked"))
    }
}
