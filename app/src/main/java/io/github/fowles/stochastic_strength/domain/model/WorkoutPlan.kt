package io.github.fowles.stochastic_strength.domain.model

data class WorkoutPlan(
    val exercises: List<PlannedExercise>,
    val locationId: Long?,
    val sessionReps: Int = 10,
) {
    val estimatedDurationSeconds: Int
        get() = exercises.sumOf { it.state.currentSets * it.secondsPerSet + it.warmupSets.size * PlannedExercise.SECONDS_PER_WARMUP_SET }
}
