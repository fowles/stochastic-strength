package io.github.fowles.stochastic_strength.data.model

data class CoefficientHistory(
    val id: Long = 0,
    val exerciseId: Long,
    val previousCoefficient: Float? = null,
    val coefficient: Float,
    val heuristicName: String,
    val heuristicMetadata: String? = null,
    val computedAt: Long,
)
