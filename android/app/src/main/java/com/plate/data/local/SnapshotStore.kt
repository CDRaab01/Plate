package com.plate.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.snapshotDataStore by preferencesDataStore(name = "plate_snapshots")

/**
 * Last-known snapshots the app persists so out-of-process surfaces (the home-screen Glance widget)
 * can render without touching the network or Room. Each surface serializes its own small view to a
 * JSON string keyed by name; nothing security-sensitive lives here (it's the same aggregates the
 * Home screen already shows), so plain DataStore, no encryption. Mirrors Magpie's `SnapshotStore`.
 */
@Singleton
class SnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun save(key: String, json: String) {
        context.snapshotDataStore.edit { it[stringPreferencesKey(key)] = json }
    }

    suspend fun read(key: String): String? =
        context.snapshotDataStore.data.map { it[stringPreferencesKey(key)] }.first()

    companion object {
        /** Today's remaining-macros view, written by Home/Diary and read by the widget. */
        const val WIDGET = "widget"
    }
}
