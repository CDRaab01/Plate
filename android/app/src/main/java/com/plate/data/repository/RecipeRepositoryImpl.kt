package com.plate.data.repository

import com.plate.data.remote.ApiService
import com.plate.data.remote.DiscoveredRecipe
import com.plate.data.remote.RecipeCreate
import com.plate.data.remote.RecipeImportRequest
import com.plate.data.remote.RecipeItemIn
import com.plate.data.remote.RecipeItemsReplace
import com.plate.data.remote.RecipeOut
import com.plate.data.remote.RecipeUpdate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecipeRepositoryImpl @Inject constructor(
    private val api: ApiService,
) : RecipeRepository {

    override suspend fun list(): List<RecipeOut> = api.getRecipes()

    override suspend fun get(id: String): RecipeOut = api.getRecipe(id)

    override suspend fun create(
        name: String,
        description: String?,
        items: List<RecipeItemIn>,
    ): RecipeOut = api.createRecipe(RecipeCreate(name = name, description = description, items = items))

    override suspend fun rename(id: String, name: String?, description: String?): RecipeOut =
        api.updateRecipe(id, RecipeUpdate(name = name, description = description))

    override suspend fun replaceItems(id: String, items: List<RecipeItemIn>): RecipeOut =
        api.replaceRecipeItems(id, RecipeItemsReplace(items = items))

    override suspend fun delete(id: String) = api.deleteRecipe(id)

    override suspend fun discover(query: String): List<DiscoveredRecipe> =
        api.discoverRecipes(query)

    override suspend fun importRecipe(sourceId: String): RecipeOut =
        api.importRecipe(RecipeImportRequest(sourceId = sourceId))
}
