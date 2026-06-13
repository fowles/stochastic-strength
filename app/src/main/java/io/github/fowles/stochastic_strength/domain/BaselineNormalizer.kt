package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

data class ExerciseCoefficientSnapshot(
    val exercise: Exercise,
    val seedCoefficient: Float,
    val currentCoefficient: Float,
)

data class BaselineNormalizationInput(
    val sets: List<WorkoutSet>,
    val exercises: List<ExerciseCoefficientSnapshot>,
    val baselines: Map<MuscleGroup, Float>,
)

data class BaselineNormalizationProposal(
    val muscleGroup: MuscleGroup,
    val scale: Float,
    val metadata: String? = null,
)

interface BaselineNormalizer {
    val name: String
    fun compute(input: BaselineNormalizationInput): List<BaselineNormalizationProposal>
}
