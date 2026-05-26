package io.github.fowles.stochastic_strength.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.fowles.stochastic_strength.ui.exercises.ExerciseDetailScreen
import io.github.fowles.stochastic_strength.ui.exercises.ExercisesScreen
import io.github.fowles.stochastic_strength.ui.history.HistoryScreen
import io.github.fowles.stochastic_strength.ui.home.HomeScreen
import io.github.fowles.stochastic_strength.ui.locations.LocationEditScreen
import io.github.fowles.stochastic_strength.ui.locations.LocationsScreen
import io.github.fowles.stochastic_strength.ui.summary.SummaryScreen
import io.github.fowles.stochastic_strength.ui.workout.WorkoutScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onStartWorkout = { navController.navigate("workout") },
                onHistory = { navController.navigate("history") },
                onExercises = { navController.navigate("exercises") },
                onLocations = { navController.navigate("locations") },
            )
        }
        composable("history") {
            HistoryScreen(
                onSessionTap = { sessionId -> navController.navigate("summary/$sessionId") },
                onBack = { navController.popBackStack() },
            )
        }
        composable("locations") {
            LocationsScreen(
                onLocationTap = { locationId -> navController.navigate("location/$locationId") },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "location/{locationId}",
            arguments = listOf(navArgument("locationId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val locationId = backStackEntry.arguments!!.getLong("locationId")
            LocationEditScreen(
                locationId = locationId,
                onBack = { navController.popBackStack() },
            )
        }
        composable("exercises") {
            ExercisesScreen(
                onExerciseTap = { exerciseId -> navController.navigate("exercise/$exerciseId") },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "exercise/{exerciseId}",
            arguments = listOf(navArgument("exerciseId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val exerciseId = backStackEntry.arguments!!.getLong("exerciseId")
            ExerciseDetailScreen(
                exerciseId = exerciseId,
                onBack = { navController.popBackStack() },
            )
        }
        composable("workout") {
            WorkoutScreen(
                onWorkoutDone = {
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
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
                onBack = { navController.popBackStack() },
            )
        }
    }
}
