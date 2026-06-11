package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback

interface ProgressionEngine {
    fun computeNextBaseline(
        baseline: Float,
        feedbacks: List<SetFeedback>,
        minReductionFraction: Float = 0f,
        sessionReps: Int = 5,
    ): Float

    val repOptions: List<Int>

    fun toOneRepMax(weight: Float, reps: Int): Float
    fun fromOneRepMax(oneRepMax: Float, reps: Int): Float
    fun scaleReps(weight: Float, from: Int, to: Int): Float
}
