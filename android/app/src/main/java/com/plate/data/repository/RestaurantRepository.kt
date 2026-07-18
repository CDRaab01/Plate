package com.plate.data.repository

import com.plate.data.remote.MenuParseResponse
import com.plate.data.remote.RestaurantComponentIn
import com.plate.data.remote.RestaurantLogSelection
import com.plate.data.remote.RestaurantOut

/**
 * Restaurant/chain checkbox templates: list (own + shared), build (manually or from a parsed
 * menu link), edit, and log ticked components into the diary.
 */
interface RestaurantRepository {
    suspend fun list(): List<RestaurantOut>
    suspend fun get(id: String): RestaurantOut
    suspend fun create(
        name: String,
        menuUrl: String?,
        shared: Boolean,
        components: List<RestaurantComponentIn>,
    ): RestaurantOut
    suspend fun update(id: String, name: String?, menuUrl: String?, shared: Boolean?): RestaurantOut
    suspend fun replaceComponents(id: String, components: List<RestaurantComponentIn>): RestaurantOut
    suspend fun delete(id: String)

    /** Fetch + parse a menu URL into an editable component draft (never persisted server-side). */
    suspend fun parseMenu(url: String): MenuParseResponse

    /** Log the ticked components for [date]/[meal]; returns how many entries were created. */
    suspend fun log(id: String, date: String, meal: String, selections: List<RestaurantLogSelection>): Int
}
