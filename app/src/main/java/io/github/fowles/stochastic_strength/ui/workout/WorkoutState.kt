package io.github.fowles.stochastic_strength.ui.workout

import io.github.fowles.stochastic_strength.data.model.ExerciseHurtState
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.domain.WorkoutGenerator
import io.github.fowles.stochastic_strength.domain.WorkoutSequence
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
        /** Completed working sets per exercise id. [setIndex] is always this exercise's entry. */
        val done: Map<Long, Int> = emptyMap(),
    ) : WorkoutState {
        val plannedExercise: PlannedExercise get() = plan.exercises[exerciseIndex]
        val totalSets: Int get() = plannedExercise.sets
        val positionLabel: String get() = WorkoutSequence.positionLabel(plan.exercises, exerciseIndex, setIndex)
        val currentWarmupSet get() = warmupSetIndex?.let { plannedExercise.warmupSets[it] }
    }

    data class PlanPreview(
        val plan: WorkoutPlan,
        val locationName: String? = null,
        val repMin: Int = 5,
        val repMax: Int = 10,
        val detraining: DetrainingNotice? = null,
        /** The exercise-count slider's value: the minimum plan size the app maintains. */
        val targetCount: Int = WorkoutGenerator.DEFAULT_EXERCISE_COUNT,
        /** Rows the user chose that the generator would have filtered out. */
        val rowFlags: Map<Long, RowFlag> = emptyMap(),
        /** True once the user has made any change to the plan. */
        val edited: Boolean = false,
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
        /** Progress once this rest ends: after the logged set, or — for a staged rest — the commit target's. */
        val done: Map<Long, Int> = emptyMap(),
        /** Set only when the just-logged set was HURT: what its exercise's hurt-state row was before it, so undo can restore exactly that (never re-derived at undo time). */
        val hurtUndo: HurtUndo? = null,
    ) : WorkoutState

    data class Done(val sessionId: Long) : WorkoutState
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

/** The exercise's [ExerciseHurtState] row before a just-logged HURT set; null means no row existed. */
data class HurtUndo(val exerciseId: Long, val previousRow: ExerciseHurtState?)

enum class RowFlag { NOT_AT_LOCATION, TRAINED_RECENTLY }
