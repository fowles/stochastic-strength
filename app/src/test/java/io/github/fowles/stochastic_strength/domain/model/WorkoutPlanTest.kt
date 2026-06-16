package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutPlanTest {

    private fun ex(id: Long) = Exercise(
        id = id,
        name = "Ex$id",
        primaryMuscle = MuscleGroup.CHEST,
        equipment = Equipment.BARBELL,
    )

    @Test
    fun `estimatedDurationSeconds sums formula when no exercises have overrides`() {
        val plan = WorkoutPlan(
            exercises = listOf(
                PlannedExercise(exercise = ex(1L)),
                PlannedExercise(exercise = ex(2L)),
            ),
            locationId = null,
        )
        // each: 3 × 135 + 0 = 405. Total 810.
        assertEquals(810, plan.estimatedDurationSeconds)
    }

    @Test
    fun `estimatedDurationSeconds sums override and formula across exercises`() {
        val plan = WorkoutPlan(
            exercises = listOf(
                PlannedExercise(exercise = ex(1L), estimatedSecondsOverride = 500),
                PlannedExercise(exercise = ex(2L)),
            ),
            locationId = null,
        )
        // 500 + (3 × 135) = 905.
        assertEquals(905, plan.estimatedDurationSeconds)
    }

    @Test
    fun `estimatedDurationSeconds with all overrides simply sums them`() {
        val plan = WorkoutPlan(
            exercises = listOf(
                PlannedExercise(exercise = ex(1L), estimatedSecondsOverride = 500),
                PlannedExercise(exercise = ex(2L), estimatedSecondsOverride = 700),
            ),
            locationId = null,
        )
        assertEquals(1200, plan.estimatedDurationSeconds)
    }
}
