package com.plate.ui.restaurant

import com.plate.data.remote.RestaurantComponentIn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A bundled chain preset (assets/restaurant_presets.json): a restaurant importable in one tap.
 * Components carry inline official macros (the chain's published nutrition), so importing needs
 * no search resolution — it's an ordinary create, shared by default so one import serves every
 * account on the server. Numbers were transcribed from each chain's official nutrition
 * publication at authoring time and are user-editable after import.
 */
@Serializable
data class RestaurantPreset(
    val name: String,
    @SerialName("menu_url") val menuUrl: String? = null,
    val components: List<RestaurantComponentIn> = emptyList(),
)

@Serializable
data class RestaurantPresetsFile(
    val presets: List<RestaurantPreset> = emptyList(),
)

/** Pure parser for the bundled presets JSON — unit-testable without Android. */
object PresetParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): List<RestaurantPreset> =
        json.decodeFromString<RestaurantPresetsFile>(raw).presets
}
