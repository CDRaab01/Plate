package com.plate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.plate.ui.navigation.PlateNavHost
import com.plate.ui.onboarding.PlateOnboarding
import com.plate.ui.theme.PlateTheme
import com.plate.util.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import design.pulse.ui.theme.ThemePref
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appPreferences: AppPreferences

    // The static launcher shortcut (if any) that opened us. Held across the onboarding + login
    // gates so a shortcut tapped while signed out / not onboarded still lands on its target once
    // the user is through — the target is never dropped. Updated on a warm re-launch too
    // (launchMode=singleTop delivers those via onNewIntent), so the nav can react.
    private var shortcutTarget by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        shortcutTarget = intent?.shortcutTarget()
        setContent {
            val themePref by appPreferences.themePref.collectAsState(initial = ThemePref.System)
            // null = still loading the flag (brief, on cold start) — render nothing to avoid a flash
            // of the app before onboarding, or vice-versa.
            val onboarded by appPreferences.hasOnboarded.collectAsState(initial = null)
            PlateTheme(darkTheme = themePref.resolveDarkTheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (onboarded) {
                        false -> PlateOnboarding(
                            onFinish = { lifecycleScope.launch { appPreferences.setOnboarded() } },
                        )
                        true -> PlateNavHost(
                            shortcutTarget = shortcutTarget,
                            onShortcutHandled = { shortcutTarget = null },
                        )
                        null -> Unit // loading
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shortcutTarget = intent.shortcutTarget()
    }
}

/** The `plate://shortcut/<target>` segment for a launcher-shortcut VIEW intent, else null. */
private fun Intent.shortcutTarget(): String? =
    if (action == Intent.ACTION_VIEW && data?.scheme == "plate") data?.lastPathSegment else null
