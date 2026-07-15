package com.plate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plate.BuildConfig
import com.plate.data.remote.ApiService
import com.plate.data.remote.UserOut
import com.plate.data.remote.UserSettingsUpdate
import com.plate.util.AppPreferences
import com.plate.util.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import design.pulse.ui.theme.ThemePref
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val api: ApiService,
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
