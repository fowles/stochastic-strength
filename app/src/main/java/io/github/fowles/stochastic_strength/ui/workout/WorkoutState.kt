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
        val repMin: Int = 5,
        val repMax: Int = 10,
        val detraining: DetrainingNotice? = null,
    ) : WorkoutState

    data class Resting(
        val plan: WorkoutPlan,
        val exerciseIndex: Int,
        val completedSetIndex: Int,
        val sessionId: Long,
        val secondsRemaining: Int,
        val lastFeedback: SetFeedback?,
        val weightReductionApplied: Boolean = false,
        val weightAtSetStart: Float,
        val currentSetRowId: Long,
        val staged: StagedAction? = null,
        val restQuip: String? = null,
    ) : WorkoutState

    data class Done(
        val sessionId: Long,
        val plan: WorkoutPlan,
        val startTime: Long,
        val endTime: Long,
    ) : WorkoutState
}

enum class StagedKind { SWAP, ADJUST_WEIGHT, END_EXERCISE, STOP_WORKOUT, WARMUP_DONE }

data class StagedAction(
    val kind: StagedKind,
    val undoTarget: WorkoutState.ActiveSet,
    val commitTarget: WorkoutState.ActiveSet?,
    val pendingSwap: PendingSwap? = null,
)

data class PendingSwap(
    val reason: ExerciseRemovalReason,
    val exerciseId: Long,
    val locationId: Long?,
)

/** Informational "you've been away — starting lighter" banner; carries no adjustable state. */
data class DetrainingNotice(val weeksOff: Int)
