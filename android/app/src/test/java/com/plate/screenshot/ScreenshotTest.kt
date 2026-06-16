package com.plate.screenshot

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.plate.ui.auth.LoginContent
import com.plate.ui.auth.RegisterContent
import com.plate.ui.theme.PlateTheme
import com.plate.util.UiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshot tests (Robolectric native graphics + Roborazzi) — render the Plate auth UI to
 * PNGs without a device or emulator. Run with `:app:testDebugUnitTest`; images land in
 * `app/screenshots/`. Record with `-Proborazzi.test.record=true`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

    // A small tolerance so sub-pixel AA / font-hinting noise across machines doesn't flag a diff.
    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.03f),
    )

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            PlateTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) { content() }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = roborazziOptions)
    }

    @Test fun login_light() = capture("login_light", dark = false) { LoginScene() }
    @Test fun login_dark() = capture("login_dark", dark = true) { LoginScene() }
    @Test fun register_light() = capture("register_light", dark = false) { RegisterScene() }
    @Test fun register_dark() = capture("register_dark", dark = true) { RegisterScene() }
}

@Composable
private fun LoginScene() {
    LoginContent(
        email = "casey@plate.app",
        onEmailChange = {},
        password = "supersecret",
        onPasswordChange = {},
        state = UiState.Idle,
        onSubmit = {},
        onNavigateToRegister = {},
        onNavigateToForgotPassword = {},
    )
}

@Composable
private fun RegisterScene() {
    RegisterContent(
        name = "Casey Raab",
        onNameChange = {},
        email = "casey@plate.app",
        onEmailChange = {},
        password = "supersecret",
        onPasswordChange = {},
        state = UiState.Idle,
        onSubmit = {},
        onNavigateToLogin = {},
    )
}
