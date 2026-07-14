package com.plate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                        true -> PlateNavHost()
                        null -> Unit // loading
                    }
                }
            }
        }
    }
}
