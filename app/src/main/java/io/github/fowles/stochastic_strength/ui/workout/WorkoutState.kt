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
        val warmupSetIndex: Int? = null,
        val timerSecondsRemaining: Int? = null,
    ) : WorkoutState {
        val plannedExercise: PlannedExercise get() = plan.exercises[exerciseIndex]
        val totalSets: Int get() = PlannedExercise.DEFAULT_SETS
        val currentWarmupSet get() = warmupSetIndex?.let { plannedExercise.warmupSets[it] }
    }

    data class PlanPreview(
        val plan: WorkoutPlan,
        val locationName: String? = null,
    ) : WorkoutState

    data class Resting(
        val plan: WorkoutPlan,
        val exerciseIndex: Int,
        val completedSetIndex: Int,
        val sessionId: Long,
        val secondsRemaining: Int,
        val lastFeedback: SetFeedback,
        val weightReductionApplied: Boolean = false,
        val weightAtSetStart: Float,
        val currentSetRowId: Long,
    ) : WorkoutState

    data class Done(
        val sessionId: Long,
        val plan: WorkoutPlan,
        val startTime: Long,
        val endTime: Long,
    ) : WorkoutState
}
