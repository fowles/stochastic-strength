package io.github.fowles.stochastic_strength.ui.workout

import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.domain.model.WorkoutPlan

sealed interface WorkoutState {
    data object Loading : WorkoutState

    data class ActiveSet(
        val plan: WorkoutPlan,
        val exerciseIndex: Int,
        val setIndex: Int,
        val sessionId: Long,
    ) : WorkoutState {
        val plannedExercise: PlannedExercise get() = plan.exercises[exerciseIndex]
        val totalSets: Int get() = plannedExercise.state.currentSets
    }

    data class Resting(
        val plan: WorkoutPlan,
        val exerciseIndex: Int,
        val completedSetIndex: Int,
        val sessionId: Long,
        val secondsRemaining: Int,
    ) : WorkoutState

    data class Done(
        val sessionId: Long,
        val plan: WorkoutPlan,
        val startTime: Long,
        val endTime: Long,
    ) : WorkoutState
}
