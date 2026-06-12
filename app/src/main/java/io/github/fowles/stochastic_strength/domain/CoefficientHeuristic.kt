package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

data class CoefficientComputationInput(
    val sets: List<WorkoutSet>,
    val sessionTimes: Map<Long, Long>,
    val exerciseMuscle: Map<Long, MuscleGroup>,
    val baselines: Map<Pair<Long, MuscleGroup>, Float>,
    val currentCoefficients: Map<Long, Float>,
)

data class CoefficientResult(
    val exerciseId: Long,
    val coefficient: Float,
    val metadata: String? = null,
)

interface CoefficientHeuristic {
    val name: String
    fun compute(input: CoefficientComputationInput): List<CoefficientResult>
}
