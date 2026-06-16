package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.Exercise

data class PlannedExercise(
    val exercise: Exercise,
    val sessionWeight: Float = 0f,
    val originalSessionWeight: Float = sessionWeight,
    val sessionReps: Int = 10,
    val warmupSets: List<WarmupSet> = emptyList(),
    val estimatedSeconds: Int = 0,
) {
    companion object {
        const val DEFAULT_SETS = 3
    }
}
