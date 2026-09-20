package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.WeightUnit

/** Prices rows in the saved-workout editor (and any other off-session context) the way the planner would. */
class RowSuggester(private val planner: WorkoutPlanner, val repMin: Int, val repMax: Int, val weightUnit: WeightUnit) {
    val typicalReps: Int get() = RepRangePicker.typical(repMin, repMax)

    /** Suggested kg for [exercise] at [reps] (null = the typical session reps); 0 when it has no weight. */
    fun weight(exercise: Exercise, reps: Int?): Float =
        if (exercise.isTimed) 0f else planner.suggestedWeight(exercise, reps ?: typicalReps)
}
