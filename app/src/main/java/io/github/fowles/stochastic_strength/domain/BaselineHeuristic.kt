package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

data class BaselineComputationInput(
    val sets: List<WorkoutSet>,
    val exerciseMuscle: Map<Long, MuscleGroup>,
    val currentCoefficients: Map<Long, Float>,
    val currentBaselines: Map<MuscleGroup, Float>,
    val recentHistory: Map<MuscleGroup, List<BaselineHistory>>,
    val sessionReps: Int,
    val minReductionFractions: Map<MuscleGroup, Float>,
    val asOf: Long,
)

data class BaselineProposal(
    val muscleGroup: MuscleGroup,
    val newBaseline: Float,
    val metadata: String?,
)

interface BaselineHeuristic {
    val name: String
    fun compute(input: BaselineComputationInput): List<BaselineProposal>
}
