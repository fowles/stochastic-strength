package io.github.fowles.stochastic_strength.domain.model

data class WorkoutPlan(
    val exercises: List<PlannedExercise>,
    val locationId: Long?,
    val sessionReps: Int = 10,
    val sessionRejectedIds: Set<Long> = emptySet(),
    val exerciseOverrides: Map<Long, Float> = emptyMap(),     // per-exercise e1rm (manual edits)
    val detrainOverrides: Map<Long, Float> = emptyMap(),      // per-exercise e1rm (detraining)
) {
    val estimatedDurationSeconds: Int get() = exercises.sumOf { it.estimatedSeconds }
    /** Per-exercise e1rm feeding the planner: detraining first, manual edits override it. */
    val effectiveOverrides: Map<Long, Float> get() = detrainOverrides + exerciseOverrides
}
