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
import com.plate.data.remote.DailyLog
import com.plate.data.remote.FoodOut
import com.plate.data.remote.LogEntryOut
import com.plate.data.remote.MealGroup
import com.plate.data.remote.RecipeItemOut
import com.plate.data.remote.RecipeOut
import com.plate.data.remote.TotalsOut
import com.plate.ui.about.AboutContent
import com.plate.ui.auth.LoginContent
import com.plate.ui.auth.RegisterContent
import com.plate.ui.coach.CoachContent
import com.plate.ui.coach.CoachMessage
import com.plate.ui.coach.CoachUiState
import com.plate.ui.diary.DiaryContent
import com.plate.ui.food.FoodSearchContent
import com.plate.ui.navigation.PlateBottomBar
import com.plate.ui.recipe.RecipeListContent
import com.plate.ui.scan.PermissionRationale
import com.plate.ui.scan.ScanStatusBar
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
    @Test fun diary_light() = capture("diary_light", dark = false) { DiaryScene() }
    @Test fun diary_dark() = capture("diary_dark", dark = true) { DiaryScene() }
    @Test fun search_light() = capture("search_light", dark = false) { SearchScene() }
    @Test fun search_dark() = capture("search_dark", dark = true) { SearchScene() }
    @Test fun about_light() = capture("about_light", dark = false) { AboutContent(onBack = {}) }
    @Test fun about_dark() = capture("about_dark", dark = true) { AboutContent(onBack = {}) }
    @Test fun scan_permission_light() =
        capture("scan_permission_light", dark = false) { PermissionRationale(onRequestPermission = {}) }
    @Test fun scan_error_light() = capture("scan_error_light", dark = false) {
        ScanStatusBar(state = UiState.Error("No product found for that barcode"), onRetry = {})
    }
    @Test fun scan_error_dark() = capture("scan_error_dark", dark = true) {
        ScanStatusBar(state = UiState.Error("No product found for that barcode"), onRetry = {})
    }
    @Test fun coach_light() = capture("coach_light", dark = false) { CoachScene() }
    @Test fun coach_dark() = capture("coach_dark", dark = true) { CoachScene() }
    @Test fun recipes_light() = capture("recipes_light", dark = false) { RecipesScene() }
    @Test fun recipes_dark() = capture("recipes_dark", dark = true) { RecipesScene() }
    @Test fun bottom_bar_light() =
        capture("bottom_bar_light", dark = false) { PlateBottomBar(currentDestination = null, onSelect = {}) }
    @Test fun bottom_bar_dark() =
        capture("bottom_bar_dark", dark = true) { PlateBottomBar(currentDestination = null, onSelect = {}) }
}

private fun recipeItem(id: String, name: String, kcal: Double) = RecipeItemOut(
    id = id, foodId = id, foodName = name, quantity = 100.0, unit = "g", order = 0,
    kcal = kcal, proteinG = 8.0, carbsG = 20.0, fatG = 4.0,
)

@Composable
private fun RecipesScene() {
    RecipeListContent(
        recipes = listOf(
            RecipeOut(
                "1", "Overnight Oats", "Oats, milk, berries",
                listOf(recipeItem("a", "Rolled Oats", 311.0), recipeItem("b", "Blueberries", 57.0)),
                TotalsOut(368.0, 14.0, 64.0, 8.0),
            ),
            RecipeOut(
                "2", "Chicken & Rice", null,
                listOf(recipeItem("c", "Chicken Breast", 248.0), recipeItem("d", "White Rice", 206.0)),
                TotalsOut(454.0, 52.0, 45.0, 7.0),
            ),
        ),
        onEdit = {},
        onDelete = {},
        onLog = { _, _ -> },
    )
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

private fun entry(id: String, name: String, qty: Double, unit: String, kcal: Double, p: Double, c: Double, f: Double) =
    LogEntryOut(id, id, name, "2026-06-16", "", qty, unit, kcal, p, c, f)

private fun totals(entries: List<LogEntryOut>) = TotalsOut(
    kcal = entries.sumOf { it.kcal },
    proteinG = entries.sumOf { it.proteinG },
    carbsG = entries.sumOf { it.carbsG },
    fatG = entries.sumOf { it.fatG },
)

@Composable
private fun DiaryScene() {
    val breakfast = listOf(
        entry("1", "Rolled Oats", 80.0, "g", 311.0, 11.0, 54.0, 6.0),
        entry("2", "Banana", 118.0, "g", 105.0, 1.3, 27.0, 0.4),
    )
    val lunch = listOf(
        entry("3", "Grilled Chicken Breast", 150.0, "g", 248.0, 46.5, 0.0, 5.4),
        entry("4", "Brown Rice", 1.0, "serving", 216.0, 5.0, 45.0, 1.8),
    )
    val meals = listOf(
        MealGroup("breakfast", breakfast, totals(breakfast)),
        MealGroup("lunch", lunch, totals(lunch)),
        MealGroup("dinner", emptyList(), totals(emptyList())),
        MealGroup("snack", emptyList(), totals(emptyList())),
    )
    val all = breakfast + lunch
    DiaryContent(
        day = DailyLog(
            date = "2026-06-16",
            meals = meals,
            totals = totals(all),
            targets = TotalsOut(2000.0, 150.0, 200.0, 67.0),
        ),
        greeting = "Good morning",
        mealNudge = "What's for breakfast today?",
        onPrevDay = {},
        onNextDay = {},
        onToday = {},
        onEditEntry = {},
        onDeleteEntry = {},
    )
}

private fun searchFood(id: String, name: String, brand: String?, kcal: Double) = FoodOut(
    id = id,
    source = "off",
    name = name,
    brand = brand,
    kcalPer100g = kcal,
    proteinGPer100g = 0.0,
    carbsGPer100g = 0.0,
    fatGPer100g = 0.0,
)

@Composable
private fun CoachScene() {
    CoachContent(
        state = CoachUiState(
            messages = listOf(
                CoachMessage("user", "I have 600 kcal and 40g protein left. Snack ideas?"),
                CoachMessage(
                    "assistant",
                    "Try Greek yogurt with berries (~150 kcal, 15g protein) or a tin of " +
                        "tuna on toast (~250 kcal, 28g protein). Both leave room and hit protein.",
                ),
            ),
        ),
        onSend = {},
    )
}

@Composable
private fun SearchScene() {
    FoodSearchContent(
        query = "chicken",
        onQueryChange = {},
        results = UiState.Success(
            listOf(
                searchFood("1", "Chicken Breast, raw", null, 120.0),
                searchFood("2", "Grilled Chicken Strips", "Tyson", 165.0),
                searchFood("3", "Chicken Thigh, roasted", null, 209.0),
                searchFood("4", "Chicken Caesar Salad", "Deli", 142.0),
            ),
        ),
        onBack = {},
        onScan = {},
        onPhoto = {},
        onPick = {},
    )
}
