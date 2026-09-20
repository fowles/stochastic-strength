package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import androidx.compose.runtime.Immutable

/** Prices rows in the saved-workout editor (and any other off-session context) the way the planner would. */
@Immutable
class RowSuggester(private val planner: WorkoutPlanner, val repMin: Int, val repMax: Int, val weightUnit: WeightUnit) {
    val typicalReps: Int get() = RepRangePicker.typical(repMin, repMax)

    /** Whether [exercise] is a row that carries a weight at all — the planner's own rule. */
    fun canCarryWeight(exercise: Exercise): Boolean = !exercise.isTimed && planner.isLoadable(exercise)

    /** Suggested kg for [exercise] at [reps] (null = the typical session reps); 0 when it has no weight. */
    fun weight(exercise: Exercise, reps: Int?): Float =
        if (exercise.isTimed) 0f else planner.suggestedWeight(exercise, reps ?: typicalReps)
}
