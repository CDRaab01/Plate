package com.plate.ui.food

import com.plate.data.remote.FoodOut
import com.plate.data.remote.PortionOut
import com.plate.util.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val CUP = PortionOut(id = "p-cup", description = "1 cup, sliced", gramWeight = 240.0)
private val SLICE = PortionOut(id = "p-slice", description = "1 slice", gramWeight = 28.0)

private fun food(
    servingSize: Double? = null,
    kcalPerServing: Double? = null,
    servingLabel: String? = null,
) = FoodOut(
    id = "f1",
    source = "usda",
    name = "Banana",
    servingSize = servingSize,
    kcalPerServing = kcalPerServing,
    servingLabel = servingLabel,
    kcalPer100g = 100.0,
    proteinGPer100g = 1.0,
    carbsGPer100g = 22.0,
    fatGPer100g = 0.3,
)

class PortionPickerStateTest {

    // ── Defaults ─────────────────────────────────────────────────────────────

    @Test
    fun `imperial user with a bare food defaults to ounces`() {
        val state = PortionPickerState(food(), unitSystem = UnitSystem.IMPERIAL)
        assertEquals(PickerUnit.Oz, state.unit)
        assertEquals("4", state.quantityText)
    }

    @Test
    fun `metric user with a bare food defaults to grams`() {
        val state = PortionPickerState(food(), unitSystem = UnitSystem.METRIC)
        assertEquals(PickerUnit.Gram, state.unit)
        assertEquals("100", state.quantityText)
    }

    @Test
    fun `food with a serving defaults to one serving`() {
        val state = PortionPickerState(food(servingSize = 118.0))
        assertEquals(PickerUnit.Serving, state.unit)
        assertEquals("1", state.quantityText)
    }

    @Test
    fun `food with only named portions defaults to the first portion`() {
        val state = PortionPickerState(food(), portions = listOf(CUP, SLICE))
        assertEquals(PickerUnit.Named(CUP), state.unit)
        assertEquals("1", state.quantityText)
    }

    // ── Converting unit switches (the core fix: never a reset) ──────────────

    @Test
    fun `switching from named portion to grams converts the amount`() {
        val state = PortionPickerState(food(), portions = listOf(CUP))
        state.switchUnit(PickerUnit.Named(CUP))
        state.setQuantity(1.0)

        state.switchUnit(PickerUnit.Gram)
        assertEquals("240", state.quantityText)

        state.switchUnit(PickerUnit.Oz)
        assertEquals("8.47", state.quantityText) // 240 g ≈ 8.47 oz

        state.switchUnit(PickerUnit.Named(CUP))
        assertEquals("1", state.quantityText) // round-trips back to 1 cup
    }

    @Test
    fun `switching units preserves a typed gram amount`() {
        val state = PortionPickerState(food(), unitSystem = UnitSystem.METRIC)
        state.onQuantityChange("50")
        state.switchUnit(PickerUnit.Oz)
        assertEquals("1.76", state.quantityText) // 50 g ≈ 1.76 oz — not a reset to 4
    }

    @Test
    fun `serving with a gram basis converts through grams`() {
        val state = PortionPickerState(food(servingSize = 118.0))
        state.switchUnit(PickerUnit.Gram)
        assertEquals("118", state.quantityText)
        state.onQuantityChange("236")
        state.switchUnit(PickerUnit.Serving)
        assertEquals("2", state.quantityText)
    }

    @Test
    fun `serving without a gram basis falls back to default on switch`() {
        // kcalPerServing-only food (no servingSize): grams can't be derived from a serving.
        val state = PortionPickerState(food(kcalPerServing = 140.0))
        assertEquals(PickerUnit.Serving, state.unit)
        state.switchUnit(PickerUnit.Gram)
        assertEquals("100", state.quantityText) // no basis — sensible default, not garbage
    }

    // ── Stepper + presets ────────────────────────────────────────────────────

    @Test
    fun `step clamps at the smallest loggable fraction`() {
        val state = PortionPickerState(food(servingSize = 118.0))
        state.step(-1.0)
        assertEquals(0.25, state.quantity!!, 1e-9)
        state.step(1.0)
        assertEquals(1.25, state.quantity!!, 1e-9)
    }

    @Test
    fun `invalid text yields null quantity and null log args`() {
        val state = PortionPickerState(food(servingSize = 118.0))
        state.onQuantityChange("abc")
        assertNull(state.quantity)
        assertNull(state.toLogArgs())
    }

    // ── Log args ─────────────────────────────────────────────────────────────

    @Test
    fun `log args carry the portion id for named portions`() {
        val state = PortionPickerState(food(), portions = listOf(CUP))
        state.switchUnit(PickerUnit.Named(CUP))
        state.setQuantity(2.0)
        assertEquals(LogArgs(2.0, "1 cup, sliced", "p-cup"), state.toLogArgs())
    }

    @Test
    fun `log args for plain units carry no portion id`() {
        val state = PortionPickerState(food(servingSize = 118.0))
        assertEquals(LogArgs(1.0, "serving", null), state.toLogArgs())
        state.switchUnit(PickerUnit.Oz)
        state.setQuantity(4.0)
        assertEquals(LogArgs(4.0, "oz", null), state.toLogArgs())
    }

    // ── Recent re-log restore ────────────────────────────────────────────────

    @Test
    fun `recent portion restores by gram weight once portions load`() {
        val state = PortionPickerState(
            food(),
            initialQuantity = 2.0,
            initialUnit = "1 cup, sliced",
            initialPortionGramWeight = 240.0,
        )
        // Portions not loaded yet: falls back, keeps the last quantity.
        assertEquals("2", state.quantityText)

        state.updatePortions(listOf(SLICE, CUP))
        assertEquals(PickerUnit.Named(CUP), state.unit)
        assertEquals("2", state.quantityText)
        assertEquals(LogArgs(2.0, "1 cup, sliced", "p-cup"), state.toLogArgs())
    }

    @Test
    fun `recent restore matches a truncated portion label`() {
        val longPortion = PortionOut("p-long", "1 extra large restaurant-style serving bowl", 400.0)
        val state = PortionPickerState(
            food(),
            initialQuantity = 1.0,
            // The log table truncates unit labels to 32 chars.
            initialUnit = "1 extra large restaurant-style serving bowl".take(32),
            initialPortionGramWeight = 400.0,
        )
        state.updatePortions(listOf(longPortion))
        assertEquals(PickerUnit.Named(longPortion), state.unit)
    }

    @Test
    fun `late portions never override a unit the user picked`() {
        val state = PortionPickerState(
            food(),
            initialQuantity = 1.0,
            initialUnit = "1 cup, sliced",
            initialPortionGramWeight = 240.0,
        )
        state.switchUnit(PickerUnit.Gram) // user makes an explicit choice before the load lands
        state.updatePortions(listOf(CUP))
        assertEquals(PickerUnit.Gram, state.unit)
    }

    @Test
    fun `recent re-log by plain unit pre-fills quantity and unit`() {
        val state = PortionPickerState(
            food(servingSize = 118.0),
            initialQuantity = 3.0,
            initialUnit = "oz",
        )
        assertEquals(PickerUnit.Oz, state.unit)
        assertEquals("3", state.quantityText)
    }

    // ── Estimate ─────────────────────────────────────────────────────────────

    @Test
    fun `estimate uses per-serving kcal when defined`() {
        val state = PortionPickerState(food(servingSize = 118.0, kcalPerServing = 105.0))
        state.setQuantity(2.0)
        assertEquals(210.0, state.estimateKcal()!!, 1e-9)
    }

    @Test
    fun `estimate scales named portions through grams`() {
        val state = PortionPickerState(food(), portions = listOf(CUP))
        state.switchUnit(PickerUnit.Named(CUP))
        state.setQuantity(0.5)
        assertEquals(120.0, state.estimateKcal()!!, 1e-9) // 120 g of a 100 kcal/100g food
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    @Test
    fun `fraction labels render quarters and halves`() {
        assertEquals("¼", formatFraction(0.25))
        assertEquals("½", formatFraction(0.5))
        assertEquals("1", formatFraction(1.0))
        assertEquals("1½", formatFraction(1.5))
        assertEquals("2", formatFraction(2.0))
    }

    @Test
    fun `quantities format to at most two trimmed decimals`() {
        assertEquals("240", formatQuantity(240.0))
        assertEquals("8.47", formatQuantity(8.4657))
        assertEquals("0.5", formatQuantity(0.5))
    }

    @Test
    fun `count units get the stepper, mass units do not`() {
        val state = PortionPickerState(food(servingSize = 118.0))
        assertTrue(state.isCountUnit)
        state.switchUnit(PickerUnit.Gram)
        assertTrue(!state.isCountUnit)
    }
}
