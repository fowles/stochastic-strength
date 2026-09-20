package io.github.fowles.stochastic_strength.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.fowles.stochastic_strength.ui.about.AboutScreen
import io.github.fowles.stochastic_strength.ui.debug.ExerciseCoefficientDetailScreen
import io.github.fowles.stochastic_strength.ui.exercises.ExerciseDetailScreen
import io.github.fowles.stochastic_strength.ui.exercises.ExercisesScreen
import io.github.fowles.stochastic_strength.ui.history.HistoryScreen
import io.github.fowles.stochastic_strength.ui.home.HomeScreen
import io.github.fowles.stochastic_strength.ui.locations.LocationEditScreen
import io.github.fowles.stochastic_strength.ui.locations.LocationsScreen
import io.github.fowles.stochastic_strength.ui.savedworkouts.SavedWorkoutEditScreen
import io.github.fowles.stochastic_strength.ui.savedworkouts.SavedWorkoutsScreen
import io.github.fowles.stochastic_strength.ui.summary.SummaryScreen
import io.github.fowles.stochastic_strength.ui.workout.WorkoutScreen

private fun NavController.popBackStackIfResumed() {
    val entry = currentBackStackEntry ?: return
    if (entry.lifecycle.currentState != Lifecycle.State.RESUMED) return
    popBackStack()
}

private fun NavController.navigateIfResumed(route: String) {
    val entry = currentBackStackEntry ?: return
    if (entry.lifecycle.currentState != Lifecycle.State.RESUMED) return
    navigate(route)
}

private fun NavController.navigateIfResumed(route: String, builder: NavOptionsBuilder.() -> Unit) {
    val entry = currentBackStackEntry ?: return
    if (entry.lifecycle.currentState != Lifecycle.State.RESUMED) return
    navigate(route, builder)
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onStartWorkout = { navController.navigateIfResumed("workout") },
                onHistory = { navController.navigateIfResumed("history") },
                onExercises = { navController.navigateIfResumed("exercises") },
                onWorkouts = { navController.navigateIfResumed("workouts") },
                onLocations = { navController.navigateIfResumed("locations") },
                onAbout = { navController.navigateIfResumed("about") },
            )
        }
        composable("about") {
            AboutScreen(
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
                onSessionTap = { sessionId -> navController.navigateIfResumed("summary/$sessionId") },
                onBack = { navController.popBackStackIfResumed() },
            )
        }
        composable("workouts") {
            SavedWorkoutsScreen(
                onWorkoutTap = { id -> navController.navigateIfResumed("workout-edit/$id") },
                onBack = { navController.popBackStackIfResumed() },
            )
        }
        composable(
            route = "workout-edit/{workoutId}",
            arguments = listOf(navArgument("workoutId") { type = NavType.LongType }),
        ) { backStackEntry ->
            SavedWorkoutEditScreen(
                workoutId = backStackEntry.arguments!!.getLong("workoutId"),
                onBack = { navController.popBackStackIfResumed() },
            )
        }
        composable("locations") {
            LocationsScreen(
                onLocationTap = { locationId -> navController.navigateIfResumed("location/$locationId") },
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
                onExerciseTap = { exerciseId -> navController.navigateIfResumed("exercise/$exerciseId") },
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
                onDebugStats = { navController.navigateIfResumed("debug/coefficient/$exerciseId") },
            )
        }
        composable("workout") {
            WorkoutScreen(
                onWorkoutDone = {
                    // Plain navigate: this arrives on a channel once the replay finishes, not from
                    // a tap, so a backgrounded app must still leave the finished workout behind.
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                },
                onEditLocation = { locationId -> navController.navigateIfResumed("location/$locationId") },
                onExerciseTap = { exerciseId -> navController.navigateIfResumed("exercise/$exerciseId") },
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
                    navController.navigateIfResumed("home") {
                        popUpTo("home") { inclusive = true }
                    }
                },
                onBack = { navController.popBackStackIfResumed() },
                onExerciseTap = { exerciseId -> navController.navigateIfResumed("exercise/$exerciseId") },
            )
        }
    }
}
