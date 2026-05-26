package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseState

data class PlannedExercise(
    val exercise: Exercise,
    val state: ExerciseState,
    val sessionWeight: Float = 0f,
    val sessionReps: Int = 10,
    val warmupSets: List<WarmupSet> = emptyList(),
) {
    val secondsPerSet: Int
        get() = if (exercise.isUnilateral) SECONDS_PER_UNILATERAL_SET else SECONDS_PER_SET

    companion object {
        const val SECONDS_PER_SET = 135
        const val SECONDS_PER_UNILATERAL_SET = 180
        const val SECONDS_PER_WARMUP_SET = 60
    }
}
