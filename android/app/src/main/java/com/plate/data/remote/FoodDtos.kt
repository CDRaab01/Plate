package com.plate.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A canonical food returned by `/foods/search`. Carries both nutrition bases (CLAUDE.md §4). */
@Serializable
data class FoodOut(
    val id: String,
    val source: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("serving_size") val servingSize: Double? = null,
    @SerialName("serving_unit") val servingUnit: String? = null,
    /** Human serving text from the source ("2 cookies (30 g)") — shown on rows + the picker. */
    @SerialName("serving_label") val servingLabel: String? = null,
    /** Source record was missing some primary macros (zeros imputed) — badge it. */
    @SerialName("macros_incomplete") val macrosIncomplete: Boolean = false,
    @SerialName("kcal_per_100g") val kcalPer100g: Double,
    @SerialName("protein_g_per_100g") val proteinGPer100g: Double,
    @SerialName("carbs_g_per_100g") val carbsGPer100g: Double,
    @SerialName("fat_g_per_100g") val fatGPer100g: Double,
    @SerialName("kcal_per_serving") val kcalPerServing: Double? = null,
)

/** A named household measure ("1 cup, sliced" = 240 g) offered by the portion picker. */
@Serializable
data class PortionOut(
    val id: String,
    val description: String,
    @SerialName("gram_weight") val gramWeight: Double,
)

/**
 * A single food with its named portions — `GET /foods/{id}` (and the barcode lookup). Fetched
 * when the add dialog opens; search rows stay the lean [FoodOut]. Flat rather than inheriting
 * (kotlinx can't extend a serializable data class); [toFoodOut] bridges to FoodOut consumers.
 */
@Serializable
data class FoodDetailOut(
    val id: String,
    val source: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("serving_size") val servingSize: Double? = null,
    @SerialName("serving_unit") val servingUnit: String? = null,
    @SerialName("serving_label") val servingLabel: String? = null,
    @SerialName("macros_incomplete") val macrosIncomplete: Boolean = false,
    @SerialName("kcal_per_100g") val kcalPer100g: Double,
    @SerialName("protein_g_per_100g") val proteinGPer100g: Double,
    @SerialName("carbs_g_per_100g") val carbsGPer100g: Double,
    @SerialName("fat_g_per_100g") val fatGPer100g: Double,
    @SerialName("kcal_per_serving") val kcalPerServing: Double? = null,
    val portions: List<PortionOut> = emptyList(),
) {
    fun toFoodOut() = FoodOut(
        id = id,
        source = source,
        name = name,
        brand = brand,
        barcode = barcode,
        servingSize = servingSize,
        servingUnit = servingUnit,
        servingLabel = servingLabel,
        macrosIncomplete = macrosIncomplete,
        kcalPer100g = kcalPer100g,
        proteinGPer100g = proteinGPer100g,
        carbsGPer100g = carbsGPer100g,
        fatGPer100g = fatGPer100g,
        kcalPerServing = kcalPerServing,
    )
}

/**
 * A user-defined custom food (`source='user'`). Used when logging a confirmed photo estimate: the
 * portion macros are stored as a one-serving food so the entry keeps a name + a stable snapshot.
 */
@Serializable
data class FoodCreateRequest(
    val name: String,
    val brand: String? = null,
    @SerialName("serving_size") val servingSize: Double? = null,
    @SerialName("serving_unit") val servingUnit: String? = null,
    @SerialName("kcal_per_100g") val kcalPer100g: Double,
    @SerialName("protein_g_per_100g") val proteinGPer100g: Double,
    @SerialName("carbs_g_per_100g") val carbsGPer100g: Double,
    @SerialName("fat_g_per_100g") val fatGPer100g: Double,
)

@Serializable
data class LogEntryCreate(
    @SerialName("food_id") val foodId: String,
    val date: String,
    val meal: String,
    val quantity: Double,
    val unit: String,
    /** Log by named portion: the server resolves it to grams and stores its label as the unit. */
    @SerialName("portion_id") val portionId: String? = null,
)

/** Log several foods at once (the food-search multi-select add) — one POST instead of one per food. */
@Serializable
data class LogEntryBatchCreate(
    val entries: List<LogEntryCreate>,
)

/** A recently-logged food + the last portion used, for one-tap re-logging. */
@Serializable
data class RecentFoodOut(
    val food: FoodOut,
    @SerialName("last_meal") val lastMeal: String,
    @SerialName("last_quantity") val lastQuantity: Double,
    @SerialName("last_unit") val lastUnit: String,
    /** Set when the last log used a named portion — restores it in the picker. */
    @SerialName("last_portion_gram_weight") val lastPortionGramWeight: Double? = null,
)

@Serializable
data class CopyDayRequest(
    @SerialName("from_date") val fromDate: String,
    @SerialName("to_date") val toDate: String,
)

@Serializable
data class LogEntryUpdate(
    val date: String? = null,
    val meal: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
)

@Serializable
data class LogEntryOut(
    val id: String,
    @SerialName("food_id") val foodId: String? = null,
    @SerialName("food_name") val foodName: String? = null,
    val date: String,
    val meal: String,
    val quantity: Double,
    val unit: String,
    val kcal: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
)

@Serializable
data class TotalsOut(
    val kcal: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    @SerialName("fiber_g") val fiberG: Double = 0.0,
    @SerialName("sugar_g") val sugarG: Double = 0.0,
    @SerialName("sat_fat_g") val satFatG: Double = 0.0,
    @SerialName("cholesterol_mg") val cholesterolMg: Double = 0.0,
    @SerialName("sodium_mg") val sodiumMg: Double = 0.0,
)

@Serializable
data class MealGroup(
    val meal: String,
    val entries: List<LogEntryOut>,
    val totals: TotalsOut,
)

@Serializable
data class DailyLog(
    val date: String,
    val meals: List<MealGroup>,
    val totals: TotalsOut,
    val targets: TotalsOut,
    // Spotter-awareness (Phase 7): the user trained that day, so `targets` already include the
    // training-day bump and the diary shows a "trained today" hint. Defaults false for older servers.
    @SerialName("trained_today") val trainedToday: Boolean = false,
    /** Consecutive days logged, ending today (or yesterday, one grace day). 0 = no active streak. */
    val streak: Int = 0,
)
