package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.Exercise

data class PlannedExercise(
    val exercise: Exercise,
    val sessionWeight: Float = 0f,
    val originalSessionWeight: Float = sessionWeight,
    val sessionReps: Int = 10,
    val warmupSets: List<WarmupSet> = emptyList(),
    val estimatedSecondsOverride: Int? = null,
) {
    val secondsPerSet: Int = when {
        exercise.isTimed -> SECONDS_PER_TIMED_SET
        exercise.isUnilateral -> SECONDS_PER_UNILATERAL_SET
        else -> SECONDS_PER_SET
    }

    val estimatedSeconds: Int
        get() = estimatedSecondsOverride
            ?: (DEFAULT_SETS * secondsPerSet + warmupSets.size * SECONDS_PER_WARMUP_SET)

    companion object {
        const val DEFAULT_SETS = 3
        const val SECONDS_PER_SET = 135
        const val SECONDS_PER_UNILATERAL_SET = 180
        const val SECONDS_PER_WARMUP_SET = 60
        const val SECONDS_PER_TIMED_SET = 90
    }
}
