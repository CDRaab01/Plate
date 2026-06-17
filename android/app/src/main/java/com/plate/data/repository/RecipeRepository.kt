package com.plate.data.repository

import com.plate.data.remote.RecipeCreate
import com.plate.data.remote.RecipeItemIn
import com.plate.data.remote.RecipeOut

/** Saved meals / recipes (Phase 8): list, read, create, edit items, delete. */
interface RecipeRepository {
    suspend fun list(): List<RecipeOut>
    suspend fun get(id: String): RecipeOut
    suspend fun create(name: String, description: String?, items: List<RecipeItemIn>): RecipeOut
    suspend fun rename(id: String, name: String?, description: String?): RecipeOut
    suspend fun replaceItems(id: String, items: List<RecipeItemIn>): RecipeOut
    suspend fun delete(id: String)
}
