package com.plate.ui.restaurant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parses the REAL bundled asset (not a fixture copy), so a malformed edit to
 * assets/restaurant_presets.json fails CI instead of silently emptying the preset sheet.
 */
class PresetParserTest {

    private fun bundledJson(): String {
        // Unit tests run from android/app; the asset lives in the main source set.
        val candidates = listOf(
            File("src/main/assets/restaurant_presets.json"),
            File("app/src/main/assets/restaurant_presets.json"),
        )
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun `bundled presets parse and are well-formed`() {
        val presets = PresetParser.parse(bundledJson())
        assertTrue("expected at least one bundled preset", presets.isNotEmpty())
        presets.forEach { preset ->
            assertTrue(preset.name.isNotBlank())
            assertTrue("${preset.name} has no components", preset.components.isNotEmpty())
            preset.components.forEach { component ->
                assertTrue(component.category.isNotBlank())
                assertTrue(component.name.isNotBlank())
                // Presets carry official numbers inline — never a food id (that's per-server).
                assertNotNull("${component.name} is missing macros", component.macros)
                assertEquals(null, component.foodId)
                val macros = component.macros!!
                assertTrue("${component.name} kcal", macros.kcal >= 0.0)
                assertTrue(macros.proteinG >= 0.0)
                assertTrue(macros.carbsG >= 0.0)
                assertTrue(macros.fatG >= 0.0)
                assertTrue(component.quantity > 0.0)
            }
        }
    }

    @Test
    fun `parser tolerates unknown keys and empty files`() {
        assertEquals(emptyList<RestaurantPreset>(), PresetParser.parse("""{"presets":[]}"""))
        val parsed = PresetParser.parse(
            """{"presets":[{"name":"X","future_field":1,"components":[]}],"schema":2}""",
        )
        assertEquals("X", parsed.single().name)
    }
}
