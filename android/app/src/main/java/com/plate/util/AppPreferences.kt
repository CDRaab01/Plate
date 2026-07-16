package com.plate.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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

        // Retention nudges (Tier W2b): opt-in local reminders, quiet-hours respected. All
        // client-side — no server involvement.
        private val NUDGES_ENABLED = booleanPreferencesKey("pref_nudges_enabled")
        private val QUIET_START_HOUR = intPreferencesKey("pref_quiet_start_hour")
        private val QUIET_END_HOUR = intPreferencesKey("pref_quiet_end_hour")
        private val LAST_LOG_EPOCH_DAY = longPreferencesKey("pref_last_log_epoch_day")

        /** Quiet hours default to 22:00–07:00 — no nudge fires overnight unless the user widens them. */
        const val DEFAULT_QUIET_START = 22
        const val DEFAULT_QUIET_END = 7
    }

    /**
     * Whether retention nudges (meal reminders + an evening "nothing logged" nudge) are enabled.
     * Opt-in: defaults to false so the app never nudges without the user turning it on.
     */
    val nudgesEnabled: Flow<Boolean> =
        context.prefsDataStore.data.map { it[NUDGES_ENABLED] ?: false }

    suspend fun setNudgesEnabled(value: Boolean) {
        context.prefsDataStore.edit { it[NUDGES_ENABLED] = value }
    }

    /** Start of quiet hours (hour-of-day, 0–23). No nudge is posted at/after this until quiet end. */
    val quietStartHour: Flow<Int> =
        context.prefsDataStore.data.map { it[QUIET_START_HOUR] ?: DEFAULT_QUIET_START }

    /** End of quiet hours (hour-of-day, 0–23). Nudges resume at this hour. */
    val quietEndHour: Flow<Int> =
        context.prefsDataStore.data.map { it[QUIET_END_HOUR] ?: DEFAULT_QUIET_END }

    suspend fun setQuietHours(startHour: Int, endHour: Int) {
        context.prefsDataStore.edit {
            it[QUIET_START_HOUR] = startHour.coerceIn(0, 23)
            it[QUIET_END_HOUR] = endHour.coerceIn(0, 23)
        }
    }

    /**
     * The local epoch-day (days since 1970-01-01, local zone) on which the user last logged food.
     * The evening "nothing logged today" nudge skips itself when this equals today. Read
     * synchronously by the nudge receiver, so it's stored as a plain long.
     */
    val lastLogEpochDay: Flow<Long> =
        context.prefsDataStore.data.map { it[LAST_LOG_EPOCH_DAY] ?: 0L }

    suspend fun setLastLogEpochDay(day: Long) {
        context.prefsDataStore.edit { it[LAST_LOG_EPOCH_DAY] = day }
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
