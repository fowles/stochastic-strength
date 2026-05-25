package io.github.fowles.stochastic_strength.domain.model

data class WorkoutPlan(
    val exercises: List<PlannedExercise>,
    val locationId: Long?,
) {
    val estimatedDurationSeconds: Int
        get() = exercises.sumOf { it.state.currentSets } * SECONDS_PER_SET

    companion object {
        const val SECONDS_PER_SET = 135
    }
}
