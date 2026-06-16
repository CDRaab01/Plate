package com.plate.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.plate.ui.auth.ForgotPasswordScreen
import com.plate.ui.auth.LoginScreen
import com.plate.ui.auth.RegisterScreen
import com.plate.ui.diary.DiaryScreen
import com.plate.ui.diary.DiaryViewModel
import com.plate.ui.food.FoodSearchScreen

object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val FORGOT = "forgot"

    // The diary + search screens share one DiaryViewModel scoped to this graph, so logging a
    // food from search reloads the diary automatically.
    const val DIARY_GRAPH = "diary_graph"
    const val DIARY = "diary"
    const val SEARCH = "search"
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
                    viewModel = diaryViewModel,
                )
            }
            composable(Routes.SEARCH) { entry ->
                val parent = remember(entry) { navController.getBackStackEntry(Routes.DIARY_GRAPH) }
                val diaryViewModel: DiaryViewModel = hiltViewModel(parent)
                FoodSearchScreen(
                    onLogged = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                    diaryViewModel = diaryViewModel,
                )
            }
        }
    }
}
