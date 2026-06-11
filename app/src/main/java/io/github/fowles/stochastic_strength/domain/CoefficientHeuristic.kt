package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback

data class SetSnapshot(
    val targetWeight: Float,
    val feedback: SetFeedback?,
)

data class ExerciseSessionSnapshot(
    val exerciseId: Long,
    val sessionId: Long,
    val sessionTime: Long,
    val targetReps: Int,
    val muscleBaseline: Float,
    val sets: List<SetSnapshot>,
)

data class CoefficientComputationInput(
    val history: List<ExerciseSessionSnapshot>,
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
