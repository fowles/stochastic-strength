package io.github.fowles.stochastic_strength.data.model

data class BaselineHistory(
    val id: Long = 0,
    val sessionId: Long?,
    val muscleGroup: MuscleGroup,
    val previousBaseline: Float,
    val newBaseline: Float,
    val changeReason: BaselineChangeReason,
    val feedbacks: String? = null,
    val sessionReps: Int? = null,
    val minReductionFraction: Float? = null,
    val timestamp: Long,
    val heuristicName: String? = null,
    val heuristicMetadata: String? = null,
)
