package io.github.fowles.stochastic_strength.domain.model

data class WorkoutPlan(
    val exercises: List<PlannedExercise>,
    val locationId: Long?,
    val sessionReps: Int = 10,
    val sessionRejectedIds: Set<Long> = emptySet(),
    val exerciseOverrides: Map<Long, Float> = emptyMap(),     // per-exercise e1rm (manual edits)
) {
    val estimatedDurationSeconds: Int get() = exercises.sumOf { it.estimatedSeconds }
    val effectiveOverrides: Map<Long, Float> get() = exerciseOverrides
}
