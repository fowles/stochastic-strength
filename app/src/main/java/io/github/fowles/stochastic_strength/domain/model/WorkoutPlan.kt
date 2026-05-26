package io.github.fowles.stochastic_strength.domain.model

data class WorkoutPlan(
    val exercises: List<PlannedExercise>,
    val locationId: Long?,
    val sessionReps: Int = 10,
) {
    val estimatedDurationSeconds: Int
        get() = exercises.sumOf { it.state.currentSets } * SECONDS_PER_SET +
                exercises.sumOf { it.warmupSets.size } * SECONDS_PER_WARMUP_SET

    companion object {
        const val SECONDS_PER_SET = 135
        const val SECONDS_PER_WARMUP_SET = 60
    }
}
