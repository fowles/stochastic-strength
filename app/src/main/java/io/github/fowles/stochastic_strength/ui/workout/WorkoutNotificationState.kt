package io.github.fowles.stochastic_strength.ui.workout

sealed interface WorkoutNotificationState {
    data class WarmupSet(
        val exerciseName: String,
        val warmupSetLabel: String,
    ) : WorkoutNotificationState

    data class ActiveSet(
        val exerciseName: String,
        val weightLabel: String,
        val repsLabel: String,
        val setLabel: String,
    ) : WorkoutNotificationState

    data class Resting(
        val secondsRemaining: Int,
        val progressMax: Int,
        val upNextLabel: String,
    ) : WorkoutNotificationState
}
