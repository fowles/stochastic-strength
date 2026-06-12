package io.github.fowles.stochastic_strength.domain

data class CoefficientRow(
    val exerciseId: Long,
    val exerciseName: String,
    val currentCoefficient: Float,
    /** Populated only for "recently changed" rows; null otherwise. */
    val previousCoefficient: Float?,
    /** Null when the exercise has no log entry yet (only seed coefficient). */
    val computedAt: Long?,
    /** Null when the exercise has no log entry yet. */
    val heuristicName: String?,
    /** Populated only for "recently changed" rows; first 80 chars, newlines flattened. */
    val heuristicMetadataPreview: String?,
)
