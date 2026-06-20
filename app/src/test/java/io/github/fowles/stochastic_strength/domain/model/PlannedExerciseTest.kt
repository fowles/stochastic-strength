package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class PlannedExerciseTest {

    private fun exercise() = Exercise(
        id = 1L,
        name = "Ex",
        primaryMuscle = MuscleGroup.CHEST,
        equipment = Equipment.BARBELL,
    )

    @Test
    fun estimatedSeconds_defaultsToZero() {
        val pe = PlannedExercise(exercise = exercise())
        assertEquals(0, pe.estimatedSeconds)
    }

    @Test
    fun estimatedSeconds_isStoredFromConstructor() {
        val pe = PlannedExercise(exercise = exercise(), estimatedSeconds = 612)
        assertEquals(612, pe.estimatedSeconds)
    }
}
