package com.plate.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.util.UnitSystem
import design.pulse.ui.components.ProfileHeader
import design.pulse.ui.components.PulseSegmentedControl
import design.pulse.ui.components.SettingsSection
import design.pulse.ui.theme.ThemePref

/**
 * Settings, in the shared suite look (Spotter's reference): an account header, then flat hairline
 * Pulse [SettingsSection] panels for appearance, units, server, account and about — no muddy-grey
 * Material cards, so dark mode reads correctly. Repointing the server URL takes effect without a
 * rebuild via [com.plate.data.remote.HostSelectionInterceptor].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val savedUrl by viewModel.serverUrl.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var urlField by rememberSaveable(savedUrl) { mutableStateOf(savedUrl) }

    // On Android 13+ posting notifications needs a runtime grant. Ask when the user turns nudges on;
    // enable regardless of the outcome (the receiver never posts without the permission), so the
    // toggle reflects intent and re-asking is a system-settings trip rather than a dead switch.
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.setNudgesEnabled(true) }

    fun enableNudges() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setNudgesEnabled(true)
        }
    }

    LaunchedEffect(saved) {
        if (saved) {
            snackbarHostState.showSnackbar("Server URL saved")
            viewModel.clearSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProfileHeader(
                name = profile?.name ?: "Plate",
                email = profile?.email ?: "",
            )

            SettingsSection("Appearance") {
                val themePref by viewModel.themePref.collectAsState()
                PulseSegmentedControl(
                    options = ThemePref.segments.map { it.name },
                    selectedIndex = ThemePref.segments.indexOf(themePref).coerceAtLeast(0),
                    onSelect = { viewModel.setThemePref(ThemePref.segments[it]) },
                )
            }

            SettingsSection("Units") {
                val unit by viewModel.unitSystem.collectAsState()
                PulseSegmentedControl(
                    options = UnitSystem.entries.map { if (it == UnitSystem.IMPERIAL) "lb / oz" else "kg / g" },
                    selectedIndex = UnitSystem.entries.indexOf(unit).coerceAtLeast(0),
                    onSelect = { viewModel.setUnitSystem(UnitSystem.entries[it]) },
                )
            }

            SettingsSection("Reminders") {
                val enabled by viewModel.nudgesEnabled.collectAsState()
                val quietStart by viewModel.quietStartHour.collectAsState()
                val quietEnd by viewModel.quietEndHour.collectAsState()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Meal-logging reminders", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Gentle lunch & dinner nudges, plus an evening reminder only if you " +
                                "haven't logged anything.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on -> if (on) enableNudges() else viewModel.setNudgesEnabled(false) },
                    )
                }

                if (enabled) {
                    Spacer(Modifier.height(12.dp))
                    Text("Quiet hours", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "No reminders between these hours.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HourStepper(
                            label = "From",
                            hour = quietStart,
                            onChange = { viewModel.setQuietHours(it, quietEnd) },
                            modifier = Modifier.weight(1f),
                        )
                        HourStepper(
                            label = "To",
                            hour = quietEnd,
                            onChange = { viewModel.setQuietHours(quietStart, it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            SettingsSection("Server") {
                OutlinedTextField(
                    value = urlField,
                    onValueChange = { urlField = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.saveServerUrl(urlField) },
                    enabled = urlField.isNotBlank() && urlField != savedUrl,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save server URL")
                }
            }

            SettingsSection("Account") {
                Button(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    // Tonal error treatment: the errorContainer/onErrorContainer pair stays legible
                    // while still reading as a destructive action (matches Spotter).
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text("Sign out")
                }
            }

            SettingsSection("About") {
                Text(
                    "Plate ${viewModel.appVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onOpenAbout, modifier = Modifier.fillMaxWidth()) {
                    Text("Credits & data sources")
                }
            }
        }
    }
}

/** A compact -/+ stepper for an hour-of-day (0–23), wrapping at the ends. */
@Composable
private fun HourStepper(
    label: String,
    hour: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { onChange((hour + 23) % 24) }) { Text("–") }
            Text(
                "%02d:00".format(hour),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { onChange((hour + 1) % 24) }) { Text("+") }
        }
    }
}
