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

    @Test
    fun effectiveOverrides_mergesWithManualWinning() {
        // exerciseOverrides win over detrainOverrides for the same key (exercise id)
        val plan = WorkoutPlan(
            exercises = emptyList(),
            locationId = null,
            exerciseOverrides = mapOf(1L to 70f),
            detrainOverrides = mapOf(1L to 50f, 2L to 60f),
        )
        assertEquals(
            mapOf(1L to 70f, 2L to 60f),
            plan.effectiveOverrides,
        )
    }

    @Test
    fun effectiveOverrides_emptyByDefault() {
        val plan = WorkoutPlan(exercises = emptyList(), locationId = null)
        assertEquals(emptyMap<Long, Float>(), plan.effectiveOverrides)
    }
}
