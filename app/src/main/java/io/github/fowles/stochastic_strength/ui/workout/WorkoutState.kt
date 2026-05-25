package io.github.fowles.stochastic_strength.ui.workout

import io.github.fowles.stochastic_strength.data.model.SetFeedback
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
        val completedSetIndex: Int,  // may be inflated to totalSets-1 when HURT skips remaining sets
        val recordedSetIndex: Int,   // the actual set index written to DB, used for undo
        val sessionId: Long,
        val secondsRemaining: Int,
        val lastFeedback: SetFeedback,
    ) : WorkoutState

    data class Done(
        val sessionId: Long,
        val plan: WorkoutPlan,
        val startTime: Long,
        val endTime: Long,
    ) : WorkoutState
}
