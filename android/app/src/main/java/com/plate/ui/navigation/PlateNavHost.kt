package com.plate.ui.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.plate.data.local.TokenStore
import com.plate.ui.about.AboutScreen
import com.plate.ui.auth.AuthViewModel
import com.plate.ui.auth.ForgotPasswordScreen
import com.plate.ui.auth.LoginScreen
import com.plate.ui.auth.RegisterScreen
import com.plate.ui.settings.SettingsScreen
import com.plate.ui.coach.CoachScreen
import com.plate.ui.diary.DiaryScreen
import com.plate.ui.diary.DiaryViewModel
import com.plate.ui.food.FoodSearchScreen
import com.plate.ui.goals.GoalScreen
import com.plate.ui.home.HomeScreen
import com.plate.ui.metabolism.MetabolismScreen
import com.plate.ui.photo.PhotoLogScreen
import com.plate.ui.recipe.DiscoverRecipesScreen
import com.plate.ui.recipe.RecipeEditScreen
import com.plate.ui.recipe.RecipeListScreen
import com.plate.ui.scan.BarcodeScanScreen
import com.plate.ui.voice.VoiceLogScreen
import com.plate.ui.calendar.CalendarScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

object Routes {
    /**
     * The startup gate: resolves the cached-token state and routes to HOME (token present — even
     * offline, so the app opens past login with the backend down) or LOGIN (no token).
     */
    const val GATE = "gate"

    const val LOGIN = "login"
    const val REGISTER = "register"
    const val FORGOT = "forgot"

    /** The dashboard — post-login start destination and first bottom-bar tab. */
    const val HOME = "home"

    // The diary + add-food screens share one DiaryViewModel scoped to this graph, so logging a
    // food from search reloads the diary automatically.
    const val DIARY_GRAPH = "diary_graph"
    const val DIARY = "diary"
    const val SEARCH = "search"
    const val SCAN = "scan"
    const val PHOTO = "photo"
    const val LABEL = "label"
    const val VOICE = "voice"
    const val GOALS = "goals"
    const val ABOUT = "about"
    const val SETTINGS = "settings"
    const val METABOLISM = "metabolism"

    // Phase 8 top-level destinations (bottom bar) + recipe editor detail.
    const val COACH = "coach"
    const val RECIPES = "recipes"
    const val RECIPE_EDIT = "recipe_edit"
    const val RECIPE_DISCOVER = "recipe_discover"
    const val SUMMARY = "summary"

    fun recipeEdit(id: String? = null) =
        if (id == null) RECIPE_EDIT else "$RECIPE_EDIT?recipeId=$id"

    /** The pre-authentication gate: no launcher shortcut is honored while on one of these. */
    val authRoutes = setOf(GATE, LOGIN, REGISTER, FORGOT)

    /**
     * Maps a `plate://shortcut/<target>` segment (see res/xml/shortcuts.xml) to its in-app route,
     * or null for an unknown target. The three shortcut targets all live in the diary graph, so
     * navigating to them puts that graph on the back stack (its DiaryViewModel resolves).
     */
    fun shortcutRoute(target: String): String? = when (target) {
        "log" -> SEARCH
        "scan" -> SCAN
        "photo" -> PHOTO
        else -> null
    }
}

/**
 * Resolves the startup gate (the Cookbook GateViewModel pattern): a cached token — no network
 * round-trip, so it resolves identically offline — means signed in.
 */
@HiltViewModel
class GateViewModel @Inject constructor(
    private val tokenStore: TokenStore,
) : ViewModel() {
    suspend fun isSignedIn(): Boolean = tokenStore.currentAccessToken() != null
}

@Composable
fun PlateNavHost(
    navController: NavHostController = rememberNavController(),
    shortcutTarget: String? = null,
    onShortcutHandled: () -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route
    val showBar = currentRoute in TopLevelDestination.barRoutes

    // A rejected refresh token (or explicit logout) clears the session; bounce back to login.
    val authViewModel: AuthViewModel = hiltViewModel()
    LaunchedEffect(Unit) {
        authViewModel.logoutEvents.collect {
            navController.navigate(Routes.LOGIN) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Honor a launcher shortcut, but only once the user is past the auth gate (login/register/
    // forgot). A shortcut tapped while signed out is held (in MainActivity) until login lands us on
    // Home, then routed to its target here — never dropped. Re-keyed on currentRoute so it fires
    // the moment the gate is cleared; cleared immediately after so it runs once per tap.
    LaunchedEffect(shortcutTarget, currentRoute) {
        val target = shortcutTarget ?: return@LaunchedEffect
        if (currentRoute == null || currentRoute in Routes.authRoutes) return@LaunchedEffect
        Routes.shortcutRoute(target)?.let { route ->
            navController.navigate(route) { launchSingleTop = true }
        }
        onShortcutHandled()
    }

    Scaffold(
        bottomBar = {
            if (showBar) {
                PlateBottomBar(
                    currentDestination = currentDestination,
                    onSelect = { dest -> navigateToTab(navController, dest) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.GATE,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.GATE) {
                val gateViewModel: GateViewModel = hiltViewModel()
                LaunchedEffect(Unit) {
                    val target = if (gateViewModel.isSignedIn()) Routes.HOME else Routes.LOGIN
                    navController.navigate(target) {
                        popUpTo(Routes.GATE) { inclusive = true }
                    }
                }
            }
            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoginSuccess = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                    onNavigateToRegister = { navController.navigate(Routes.REGISTER) },
                    onNavigateToForgotPassword = { navController.navigate(Routes.FORGOT) },
                )
            }
            composable(Routes.REGISTER) {
                RegisterScreen(
                    onRegisterSuccess = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                    onNavigateToLogin = { navController.popBackStack() },
                )
            }
            composable(Routes.FORGOT) {
                ForgotPasswordScreen(onBack = { navController.popBackStack() })
            }

            navigation(startDestination = Routes.DIARY, route = Routes.DIARY_GRAPH) {
                composable(Routes.DIARY) { entry ->
                    val parent = remember(entry) { navController.getBackStackEntry(Routes.DIARY_GRAPH) }
                    val diaryViewModel: DiaryViewModel = hiltViewModel(parent)
                    DiaryScreen(
                        onNavigateToSearch = { navController.navigate(Routes.SEARCH) },
                        onNavigateToGoals = { navController.navigate(Routes.GOALS) },
                        onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                        viewModel = diaryViewModel,
                    )
                }
                composable(Routes.SEARCH) { entry ->
                    val parent = remember(entry) { navController.getBackStackEntry(Routes.DIARY_GRAPH) }
                    val diaryViewModel: DiaryViewModel = hiltViewModel(parent)
                    FoodSearchScreen(
                        onLogged = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                        onScan = { navController.navigate(Routes.SCAN) },
                        onPhoto = { navController.navigate(Routes.PHOTO) },
                        onLabel = { navController.navigate(Routes.LABEL) },
                        onVoice = { navController.navigate(Routes.VOICE) },
                        diaryViewModel = diaryViewModel,
                    )
                }
                composable(Routes.PHOTO) { entry ->
                    val parent = remember(entry) { navController.getBackStackEntry(Routes.DIARY_GRAPH) }
                    val diaryViewModel: DiaryViewModel = hiltViewModel(parent)
                    PhotoLogScreen(
                        onDone = { navController.popBackStack(Routes.DIARY, inclusive = false) },
                        onBack = { navController.popBackStack() },
                        diaryViewModel = diaryViewModel,
                    )
                }
                composable(Routes.LABEL) { entry ->
                    val parent = remember(entry) { navController.getBackStackEntry(Routes.DIARY_GRAPH) }
                    val diaryViewModel: DiaryViewModel = hiltViewModel(parent)
                    PhotoLogScreen(
                        onDone = { navController.popBackStack(Routes.DIARY, inclusive = false) },
                        onBack = { navController.popBackStack() },
                        labelMode = true,
                        diaryViewModel = diaryViewModel,
                    )
                }
                composable(Routes.VOICE) { entry ->
                    val parent = remember(entry) { navController.getBackStackEntry(Routes.DIARY_GRAPH) }
                    val diaryViewModel: DiaryViewModel = hiltViewModel(parent)
                    VoiceLogScreen(
                        onDone = { navController.popBackStack(Routes.DIARY, inclusive = false) },
                        onBack = { navController.popBackStack() },
                        diaryViewModel = diaryViewModel,
                    )
                }
                composable(Routes.SCAN) { entry ->
                    val parent = remember(entry) { navController.getBackStackEntry(Routes.DIARY_GRAPH) }
                    val diaryViewModel: DiaryViewModel = hiltViewModel(parent)
                    BarcodeScanScreen(
                        onLogged = { navController.popBackStack(Routes.DIARY, inclusive = false) },
                        onBack = { navController.popBackStack() },
                        diaryViewModel = diaryViewModel,
                    )
                }
                composable(Routes.GOALS) {
                    GoalScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.ABOUT) {
                    AboutScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenAbout = { navController.navigate(Routes.ABOUT) },
                        onLogout = {
                            authViewModel.logout()
                            navController.navigate(Routes.LOGIN) {
                                popUpTo(navController.graph.id) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }

            composable(Routes.HOME) {
                HomeScreen(
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    // Jump straight to food search. Navigating to this nested destination puts the
                    // diary graph on the back stack, so the search screen's DiaryViewModel resolves.
                    onAddFood = { navController.navigate(Routes.SEARCH) },
                    onOpenMetabolism = { navController.navigate(Routes.METABOLISM) },
                )
            }

            composable(Routes.METABOLISM) {
                MetabolismScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.RECIPES) {
                RecipeListScreen(
                    onCreate = { navController.navigate(Routes.recipeEdit()) },
                    onEdit = { id -> navController.navigate(Routes.recipeEdit(id)) },
                    onDiscover = { navController.navigate(Routes.RECIPE_DISCOVER) },
                )
            }
            composable(Routes.RECIPE_DISCOVER) {
                DiscoverRecipesScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = "${Routes.RECIPE_EDIT}?recipeId={recipeId}",
                arguments = listOf(
                    navArgument("recipeId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                RecipeEditScreen(
                    recipeId = entry.arguments?.getString("recipeId"),
                    onDone = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SUMMARY) {
                // Opening a calendar day parks the date (CalendarViewModel.requestDay → PendingDiaryDate)
                // and switches to the Diary tab, where DiaryViewModel jumps to that day.
                CalendarScreen(onOpenDay = { navigateToTab(navController, TopLevelDestination.DIARY) })
            }
            composable(Routes.COACH) { CoachScreen() }
        }
    }
}

/** Standard bottom-bar tab navigation: single-top, save/restore each tab's state. */
private fun navigateToTab(navController: NavHostController, dest: TopLevelDestination) {
    navController.navigate(dest.navRoute) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
