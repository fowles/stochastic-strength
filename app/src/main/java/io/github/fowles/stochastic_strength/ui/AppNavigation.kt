package io.github.fowles.stochastic_strength.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.ui.about.AboutScreen
import io.github.fowles.stochastic_strength.ui.debug.DebugStatsScreen
import io.github.fowles.stochastic_strength.ui.debug.ExerciseCoefficientDetailScreen
import io.github.fowles.stochastic_strength.ui.debug.MuscleBaselineDetailScreen
import io.github.fowles.stochastic_strength.ui.exercises.ExerciseDetailScreen
import io.github.fowles.stochastic_strength.ui.exercises.ExercisesScreen
import io.github.fowles.stochastic_strength.ui.history.HistoryScreen
import io.github.fowles.stochastic_strength.ui.home.HomeScreen
import io.github.fowles.stochastic_strength.ui.locations.LocationEditScreen
import io.github.fowles.stochastic_strength.ui.locations.LocationsScreen
import io.github.fowles.stochastic_strength.ui.summary.SummaryScreen
import io.github.fowles.stochastic_strength.ui.workout.WorkoutScreen

private fun NavController.popBackStackIfResumed() {
    val entry = currentBackStackEntry ?: return
    if (entry.lifecycle.currentState != Lifecycle.State.RESUMED) return
    popBackStack()
}

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
                onAbout = { navController.navigate("about") },
            )
        }
        composable("about") {
            AboutScreen(
                onDebug = { navController.navigate("debug") },
                onBack = { navController.popBackStackIfResumed() },
            )
        }
        composable("debug") {
            DebugStatsScreen(
                onMuscleTap = { muscle -> navController.navigate("debug/muscle/${muscle.name}") },
                onExerciseTap = { exerciseId -> navController.navigate("debug/coefficient/$exerciseId") },
                onBack = { navController.popBackStackIfResumed() },
            )
        }
        composable(
            route = "debug/muscle/{muscleGroup}",
            arguments = listOf(navArgument("muscleGroup") { type = NavType.StringType }),
        ) { backStackEntry ->
            val name = backStackEntry.arguments!!.getString("muscleGroup")!!
            MuscleBaselineDetailScreen(
                muscleGroup = MuscleGroup.valueOf(name),
                onBack = { navController.popBackStackIfResumed() },
            )
        }
        composable(
            route = "debug/coefficient/{exerciseId}",
            arguments = listOf(navArgument("exerciseId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val exerciseId = backStackEntry.arguments!!.getLong("exerciseId")
            ExerciseCoefficientDetailScreen(
                exerciseId = exerciseId,
                onBack = { navController.popBackStackIfResumed() },
            )
        }
        composable("history") {
            HistoryScreen(
                onSessionTap = { sessionId -> navController.navigate("summary/$sessionId") },
                onExerciseTap = { exerciseId -> navController.navigate("exercise/$exerciseId") },
                onBack = { navController.popBackStackIfResumed() },
            )
        }
        composable("locations") {
            LocationsScreen(
                onLocationTap = { locationId -> navController.navigate("location/$locationId") },
                onBack = { navController.popBackStackIfResumed() },
            )
        }
        composable(
            route = "location/{locationId}",
            arguments = listOf(navArgument("locationId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val locationId = backStackEntry.arguments!!.getLong("locationId")
            LocationEditScreen(
                locationId = locationId,
                onBack = { navController.popBackStackIfResumed() },
            )
        }
        composable("exercises") {
            ExercisesScreen(
                onExerciseTap = { exerciseId -> navController.navigate("exercise/$exerciseId") },
                onBack = { navController.popBackStackIfResumed() },
            )
        }
        composable(
            route = "exercise/{exerciseId}",
            arguments = listOf(navArgument("exerciseId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val exerciseId = backStackEntry.arguments!!.getLong("exerciseId")
            ExerciseDetailScreen(
                exerciseId = exerciseId,
                onBack = { navController.popBackStackIfResumed() },
            )
        }
        composable("workout") {
            WorkoutScreen(
                onWorkoutDone = {
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                },
                onEditLocation = { locationId -> navController.navigate("location/$locationId") },
                onExerciseTap = { exerciseId -> navController.navigate("exercise/$exerciseId") },
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
                onBack = { navController.popBackStackIfResumed() },
                onExerciseTap = { exerciseId -> navController.navigate("exercise/$exerciseId") },
            )
        }
    }
}
