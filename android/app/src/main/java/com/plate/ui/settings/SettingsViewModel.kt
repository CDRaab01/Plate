package com.plate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.BuildConfig
import com.plate.data.remote.ApiService
import com.plate.data.remote.UserOut
import com.plate.data.remote.UserSettingsUpdate
import com.plate.util.AppPreferences
import com.plate.util.UnitSystem
import com.plate.util.nudges.NudgeScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import design.pulse.ui.theme.ThemePref
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val api: ApiService,
    private val nudgeScheduler: NudgeScheduler,
) : ViewModel() {

    /** App version from the build, surfaced in Settings → About like Spotter. */
    val appVersion: String = BuildConfig.VERSION_NAME

    /** The currently-saved server URL (or the build-time default), for the editable field. */
    val serverUrl: StateFlow<String> = appPreferences.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BuildConfig.SERVER_URL)

    /** The lb/kg display preference (cached; server is the source of truth). */
    val unitSystem: StateFlow<UnitSystem> = appPreferences.unitSystem
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UnitSystem.IMPERIAL)

    /** Appearance choice (Dark/Light/System) — local-only, applied by the theme immediately. */
    val themePref: StateFlow<ThemePref> = appPreferences.themePref
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemePref.System)

    /** Whether retention nudges (meal reminders + evening "nothing logged" nudge) are enabled. */
    val nudgesEnabled: StateFlow<Boolean> = appPreferences.nudgesEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Quiet-hours start hour (0–23). No nudge fires at/after this until [quietEndHour]. */
    val quietStartHour: StateFlow<Int> = appPreferences.quietStartHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences.DEFAULT_QUIET_START)

    /** Quiet-hours end hour (0–23). */
    val quietEndHour: StateFlow<Int> = appPreferences.quietEndHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences.DEFAULT_QUIET_END)

    /**
     * Enable/disable retention nudges. Persists the flag and (re)arms or cancels the alarms. The
     * POST_NOTIFICATIONS runtime permission is requested by the screen before enabling on Android 13+;
     * the receiver additionally no-ops if the permission isn't granted, so this is always safe.
     */
    fun setNudgesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setNudgesEnabled(enabled)
            if (enabled) nudgeScheduler.scheduleAll() else nudgeScheduler.cancelAll()
        }
    }

    /** Update quiet hours and re-arm the alarms (next-occurrence times don't change, but re-arming is
     *  harmless and keeps everything consistent) when nudges are on. */
    fun setQuietHours(startHour: Int, endHour: Int) {
        viewModelScope.launch {
            appPreferences.setQuietHours(startHour, endHour)
            if (appPreferences.nudgesEnabled.first()) nudgeScheduler.scheduleAll()
        }
    }

    /** The signed-in account (name + email), for the Settings account header. Null until loaded. */
    private val _profile = MutableStateFlow<UserOut?>(null)
    val profile: StateFlow<UserOut?> = _profile

    fun setThemePref(value: ThemePref) {
        viewModelScope.launch { appPreferences.setThemePref(value) }
    }

    init {
        // Load the account for the header and reconcile the unit cache with the server on open.
        viewModelScope.launch {
            runCatching { api.me() }.onSuccess { me ->
                _profile.value = me
                appPreferences.setUnitSystem(UnitSystem.fromWire(me.unitSystem))
            }
        }
    }

    /** Persist the unit preference to the server, then update the local cache on success. */
    fun setUnitSystem(value: UnitSystem) {
        viewModelScope.launch {
            runCatching { api.updateSettings(UserSettingsUpdate(value.wire)) }
                .onSuccess { appPreferences.setUnitSystem(UnitSystem.fromWire(it.unitSystem)) }
        }
    }

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    fun saveServerUrl(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            appPreferences.setServerUrl(trimmed)
            _saved.value = true
        }
    }

    fun clearSaved() {
        _saved.value = false
    }
}
