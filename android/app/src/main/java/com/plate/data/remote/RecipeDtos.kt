package com.plate.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One food in a saved recipe (Phase 8). Sent on create / replace-items. */
@Serializable
data class RecipeItemIn(
    @SerialName("food_id") val foodId: String,
    val quantity: Double,
    val unit: String,
)

@Serializable
data class RecipeCreate(
    val name: String,
    val description: String? = null,
    val items: List<RecipeItemIn> = emptyList(),
)

/** PATCH /recipes/{id}: name/description only (items are replaced via PUT /items). */
@Serializable
data class RecipeUpdate(
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class RecipeItemsReplace(
    val items: List<RecipeItemIn> = emptyList(),
)

@Serializable
data class RecipeItemOut(
    val id: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("food_name") val foodName: String? = null,
    val quantity: Double,
    val unit: String,
    val order: Int,
    val kcal: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
)

/** A saved recipe with its items and read-time totals (computed server-side from current foods). */
@Serializable
data class RecipeOut(
    val id: String,
    val name: String,
    val description: String? = null,
    val items: List<RecipeItemOut> = emptyList(),
    val totals: TotalsOut,
)

/** POST /recipes/{id}/log: expand the recipe into a day's meal. */
@Serializable
data class RecipeLogRequest(
    val date: String,
    val meal: String,
)

/** A recipe-discovery search hit from GET /recipes/discover (external provider). */
@Serializable
data class DiscoveredRecipe(
    @SerialName("source_id") val sourceId: String,
    val title: String,
    val image: String? = null,
    @SerialName("ready_in_minutes") val readyInMinutes: Int? = null,
    val servings: Int? = null,
)

/** POST /recipes/import: save an external recipe (by provider id) as a Plate recipe. */
@Serializable
data class RecipeImportRequest(
    @SerialName("source_id") val sourceId: String,
)
