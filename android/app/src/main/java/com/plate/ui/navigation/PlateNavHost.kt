package com.plate.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.plate.ui.about.AboutScreen
import com.plate.ui.auth.ForgotPasswordScreen
import com.plate.ui.auth.LoginScreen
import com.plate.ui.auth.RegisterScreen
import com.plate.ui.coach.CoachScreen
import com.plate.ui.diary.DiaryScreen
import com.plate.ui.diary.DiaryViewModel
import com.plate.ui.food.FoodSearchScreen
import com.plate.ui.goals.GoalScreen
import com.plate.ui.scan.BarcodeScanScreen

object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val FORGOT = "forgot"

    // The diary + search screens share one DiaryViewModel scoped to this graph, so logging a
    // food from search reloads the diary automatically.
    const val DIARY_GRAPH = "diary_graph"
    const val DIARY = "diary"
    const val SEARCH = "search"
    const val SCAN = "scan"
    const val GOALS = "goals"
    const val ABOUT = "about"
    const val COACH = "coach"
}

@Composable
fun PlateNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.DIARY_GRAPH) {
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
                    navController.navigate(Routes.DIARY_GRAPH) {
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
                    onNavigateToAbout = { navController.navigate(Routes.ABOUT) },
                    onNavigateToCoach = { navController.navigate(Routes.COACH) },
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
                    diaryViewModel = diaryViewModel,
                )
            }
            composable(Routes.SCAN) { entry ->
                val parent = remember(entry) { navController.getBackStackEntry(Routes.DIARY_GRAPH) }
                val diaryViewModel: DiaryViewModel = hiltViewModel(parent)
                BarcodeScanScreen(
                    // Logging from a scan returns straight to the diary, popping the search step too.
                    onLogged = { navController.popBackStack(Routes.DIARY, inclusive = false) },
                    onBack = { navController.popBackStack() },
                    diaryViewModel = diaryViewModel,
                )
            }
            composable(Routes.GOALS) {
                GoalScreen(
                    onBack = {
                        navController.popBackStack()
                    },
                )
            }
            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.COACH) {
                CoachScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
