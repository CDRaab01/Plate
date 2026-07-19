package com.plate.data.repository

import com.plate.data.remote.ApiService
import com.plate.data.remote.MenuParseRequest
import com.plate.data.remote.MenuParseResponse
import com.plate.data.remote.RestaurantComponentIn
import com.plate.data.remote.RestaurantComponentsReplace
import com.plate.data.remote.RestaurantCreate
import com.plate.data.remote.RestaurantLogRequest
import com.plate.data.remote.RestaurantLogSelection
import com.plate.data.remote.RestaurantOut
import com.plate.data.remote.RestaurantUpdate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RestaurantRepositoryImpl @Inject constructor(
    private val api: ApiService,
) : RestaurantRepository {

    override suspend fun list(): List<RestaurantOut> = api.getRestaurants()

    override suspend fun get(id: String): RestaurantOut = api.getRestaurant(id)

    override suspend fun create(
        name: String,
        menuUrl: String?,
        shared: Boolean,
        components: List<RestaurantComponentIn>,
    ): RestaurantOut = api.createRestaurant(
        RestaurantCreate(name = name, menuUrl = menuUrl, shared = shared, components = components),
    )

    override suspend fun update(
        id: String,
        name: String?,
        menuUrl: String?,
        shared: Boolean?,
    ): RestaurantOut = api.updateRestaurant(
        id,
        RestaurantUpdate(name = name, menuUrl = menuUrl, shared = shared),
    )

    override suspend fun replaceComponents(
        id: String,
        components: List<RestaurantComponentIn>,
    ): RestaurantOut = api.replaceRestaurantComponents(
        id,
        RestaurantComponentsReplace(components = components),
    )

    override suspend fun delete(id: String) = api.deleteRestaurant(id)

    override suspend fun parseMenu(url: String?, text: String?): MenuParseResponse =
        api.parseMenu(MenuParseRequest(url = url, text = text))

    override suspend fun log(
        id: String,
        date: String,
        meal: String,
        selections: List<RestaurantLogSelection>,
    ): Int = api.logRestaurant(
        id,
        RestaurantLogRequest(date = date, meal = meal, selections = selections),
    ).size
}
