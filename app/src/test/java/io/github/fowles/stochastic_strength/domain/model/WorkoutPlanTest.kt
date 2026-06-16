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
    fun estimatedDurationSeconds_sumsEstimatedSecondsAcrossExercises() {
        val plan = WorkoutPlan(
            exercises = listOf(
                PlannedExercise(exercise = ex(1L), estimatedSeconds = 500),
                PlannedExercise(exercise = ex(2L), estimatedSeconds = 700),
            ),
            locationId = null,
        )
        assertEquals(1200, plan.estimatedDurationSeconds)
    }

    @Test
    fun estimatedDurationSeconds_isZeroWhenAllExercisesAreZero() {
        val plan = WorkoutPlan(
            exercises = listOf(
                PlannedExercise(exercise = ex(1L)),
                PlannedExercise(exercise = ex(2L)),
            ),
            locationId = null,
        )
        assertEquals(0, plan.estimatedDurationSeconds)
    }
}
