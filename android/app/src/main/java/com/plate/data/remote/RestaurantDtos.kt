package com.plate.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Official per-serving nutrition stated by a chain's published menu. A component carrying this
 * (instead of a foodId) makes the server mint a Food branded with the restaurant's name.
 */
@Serializable
data class ComponentMacrosIn(
    @SerialName("serving_desc") val servingDesc: String? = null,
    @SerialName("serving_grams") val servingGrams: Double? = null,
    val kcal: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("sat_fat_g") val satFatG: Double? = null,
    @SerialName("cholesterol_mg") val cholesterolMg: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
)

/** One checkbox row sent on create / replace-components. foodId XOR macros (both null = unlinked). */
@Serializable
data class RestaurantComponentIn(
    val category: String,
    val name: String,
    @SerialName("food_id") val foodId: String? = null,
    val macros: ComponentMacrosIn? = null,
    val quantity: Double = 1.0,
    val unit: String = "serving",
    @SerialName("default_checked") val defaultChecked: Boolean = false,
)

@Serializable
data class RestaurantCreate(
    val name: String,
    @SerialName("menu_url") val menuUrl: String? = null,
    val notes: String? = null,
    val shared: Boolean = true,
    val components: List<RestaurantComponentIn> = emptyList(),
)

/** PATCH /restaurants/{id}: fields only (components are replaced via PUT /components). */
@Serializable
data class RestaurantUpdate(
    val name: String? = null,
    @SerialName("menu_url") val menuUrl: String? = null,
    val notes: String? = null,
    val shared: Boolean? = null,
)

@Serializable
data class RestaurantComponentsReplace(
    val components: List<RestaurantComponentIn> = emptyList(),
)

@Serializable
data class RestaurantComponentOut(
    val id: String,
    val category: String,
    val name: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("food_name") val foodName: String? = null,
    val quantity: Double,
    val unit: String,
    val order: Int,
    @SerialName("default_checked") val defaultChecked: Boolean = false,
    val kcal: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
)

/**
 * A restaurant checkbox template. Components arrive flat and ordered; the client groups them by
 * category. Shared restaurants are visible to every account; edit/delete only when [isOwner].
 */
@Serializable
data class RestaurantOut(
    val id: String,
    val name: String,
    @SerialName("menu_url") val menuUrl: String? = null,
    val notes: String? = null,
    val shared: Boolean = true,
    @SerialName("is_owner") val isOwner: Boolean = true,
    val components: List<RestaurantComponentOut> = emptyList(),
)

/** One ticked checkbox: quantity null = the component's default portion. */
@Serializable
data class RestaurantLogSelection(
    @SerialName("component_id") val componentId: String,
    val quantity: Double? = null,
)

/** POST /restaurants/{id}/log: the ticked components into a day's meal. */
@Serializable
data class RestaurantLogRequest(
    val date: String,
    val meal: String,
    val selections: List<RestaurantLogSelection>,
)

/** POST /restaurants/parse-menu request. */
@Serializable
data class MenuParseRequest(
    val url: String,
)

/**
 * One draft component from a parsed menu. [source] "official" rows carry [macros] (published
 * numbers, saved as-is); "estimate" rows resolved to [foodId] via trusted search, or neither at
 * low confidence ("not linked — tap to search").
 */
@Serializable
data class MenuParseComponent(
    val category: String,
    val name: String,
    val source: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("food_name") val foodName: String? = null,
    val macros: ComponentMacrosIn? = null,
    val quantity: Double,
    val unit: String,
    val kcal: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    val confidence: Double,
)

/** The parse draft — edited client-side, then saved via an ordinary POST /restaurants. */
@Serializable
data class MenuParseResponse(
    @SerialName("restaurant_name") val restaurantName: String? = null,
    @SerialName("menu_url") val menuUrl: String,
    val components: List<MenuParseComponent> = emptyList(),
    @SerialName("low_confidence") val lowConfidence: Boolean = false,
    val note: String? = null,
)
