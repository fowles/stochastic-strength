package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.MuscleGroup

data class WorkoutPlan(
    val exercises: List<PlannedExercise>,
    val locationId: Long?,
    val sessionReps: Int = 10,
    val sessionRejectedIds: Set<Long> = emptySet(),
    val strengthOverrides: Map<MuscleGroup, Float> = emptyMap(),
    val detrainOverrides: Map<MuscleGroup, Float> = emptyMap(),
) {
    val estimatedDurationSeconds: Int
        get() = exercises.sumOf { it.estimatedSeconds }

    /** Baselines feeding the planner: detraining first, manual edits override it. */
    val effectiveOverrides: Map<MuscleGroup, Float>
        get() = detrainOverrides + strengthOverrides
}
