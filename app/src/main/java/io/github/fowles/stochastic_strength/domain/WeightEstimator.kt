package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseState
import kotlin.math.roundToInt

object WeightEstimator {
    fun estimate(
        exercise: Exercise,
        allExercises: List<Exercise>,
        allStates: Map<Long, ExerciseState>,
    ): Float {
        val targetCoeff = ExerciseCoefficients.byName[exercise.name] ?: return 0f
        if (targetCoeff == 0f) return 0f

        val baselines = allExercises
            .filter { it.id != exercise.id && it.primaryMuscle == exercise.primaryMuscle }
            .mapNotNull { other ->
                val w = allStates[other.id]?.currentWeight?.takeIf { it > 0f } ?: return@mapNotNull null
                val c = ExerciseCoefficients.byName[other.name]?.takeIf { it > 0f } ?: return@mapNotNull null
                w / c
            }

        if (baselines.isEmpty()) return 0f
        return roundToPlate(baselines.average().toFloat() * targetCoeff)
    }

    private fun roundToPlate(weight: Float): Float =
        (weight / 2.5f).roundToInt() * 2.5f
}
