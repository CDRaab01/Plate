package com.plate.data.repository

import com.plate.data.remote.DiscoveredRecipe
import com.plate.data.remote.RecipeCreate
import com.plate.data.remote.RecipeItemIn
import com.plate.data.remote.RecipeOut

/** Saved meals / recipes (Phase 8): list, read, create, edit items, delete + external discovery. */
interface RecipeRepository {
    suspend fun list(): List<RecipeOut>
    suspend fun get(id: String): RecipeOut
    suspend fun create(name: String, description: String?, items: List<RecipeItemIn>): RecipeOut
    suspend fun rename(id: String, name: String?, description: String?): RecipeOut
    suspend fun replaceItems(id: String, items: List<RecipeItemIn>): RecipeOut
    suspend fun delete(id: String)

    /** Search external recipes by free text. */
    suspend fun discover(query: String): List<DiscoveredRecipe>

    /** Import an external recipe (by provider id) as a saved recipe. */
    suspend fun importRecipe(sourceId: String): RecipeOut
}
