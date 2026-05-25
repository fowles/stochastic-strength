package io.github.fowles.stochastic_strength.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.fowles.stochastic_strength.ui.home.HomeScreen
import io.github.fowles.stochastic_strength.ui.summary.SummaryScreen
import io.github.fowles.stochastic_strength.ui.workout.WorkoutScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(onStartWorkout = { navController.navigate("workout") })
        }
        composable("workout") {
            WorkoutScreen(
                onWorkoutDone = { sessionId ->
                    navController.navigate("summary/$sessionId") {
                        popUpTo("workout") { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = "summary/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments!!.getLong("sessionId")
            SummaryScreen(
                sessionId = sessionId,
                onDone = {
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                },
            )
        }
    }
}
