package com.plate.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.plate.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import design.pulse.ui.theme.ThemePref
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * General app preferences (server URL, future settings), DataStore-backed so they survive process
 * death. Kept separate from [com.plate.data.local.TokenStore]'s auth store. Mirrors Spotter's
 * `AppPreferences`.
 */
private val Context.prefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "plate_prefs")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private val SERVER_URL = stringPreferencesKey("pref_server_url")
        private val UNIT_SYSTEM = stringPreferencesKey("pref_unit_system")
        private val THEME_PREF = stringPreferencesKey("pref_theme")
        private val ONBOARDED = booleanPreferencesKey("pref_onboarded")
    }

    /** Whether the first-run onboarding has been completed. False until finished/skipped once. */
    val hasOnboarded: Flow<Boolean> = context.prefsDataStore.data.map { it[ONBOARDED] ?: false }

    suspend fun setOnboarded() {
        context.prefsDataStore.edit { it[ONBOARDED] = true }
    }

    /** Appearance choice (Dark/Light/System), resolved to a boolean in the theme. Defaults to System. */
    val themePref: Flow<ThemePref> = context.prefsDataStore.data.map { prefs ->
        prefs[THEME_PREF]?.let { runCatching { ThemePref.valueOf(it) }.getOrNull() } ?: ThemePref.System
    }

    suspend fun setThemePref(value: ThemePref) {
        context.prefsDataStore.edit { it[THEME_PREF] = value.name }
    }

    /** Base URL of the Plate server. Defaults to the build-time value when unset. */
    val serverUrl: Flow<String> = context.prefsDataStore.data.map { prefs ->
        prefs[SERVER_URL]?.takeIf { it.isNotBlank() } ?: BuildConfig.SERVER_URL
    }

    suspend fun setServerUrl(value: String) {
        context.prefsDataStore.edit { it[SERVER_URL] = value }
    }

    /**
     * The cached lb/kg display preference. The server (`/users/me`) is the source of truth; this
     * cache lets the UI format weights/quantities synchronously. Defaults to imperial when unset.
     */
    val unitSystem: Flow<UnitSystem> = context.prefsDataStore.data.map { prefs ->
        UnitSystem.fromWire(prefs[UNIT_SYSTEM])
    }

    suspend fun setUnitSystem(value: UnitSystem) {
        context.prefsDataStore.edit { it[UNIT_SYSTEM] = value.wire }
    }
}
