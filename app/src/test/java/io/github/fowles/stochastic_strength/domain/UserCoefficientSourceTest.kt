package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserCoefficientSourceTest {

    private val bench = Exercise(
        id = 1L,
        name = "Barbell Bench Press",
        primaryMuscle = MuscleGroup.CHEST,
        equipment = Equipment.BARBELL,
    )

    @Test
    fun userCoefficientTakesPriorityOverGlobal() {
        val source = UserCoefficientSource(mapOf(1L to 0.75f))
        assertEquals(0.75f, source.get(bench)!!, 0.001f)
    }

    @Test
    fun fallsBackToGlobalWhenNoUserCoefficient() {
        val source = UserCoefficientSource(emptyMap())
        assertEquals(1.0f, source.get(bench)!!, 0.001f)
    }

    @Test
    fun returnsNullForUnknownExerciseWithNoUserCoefficient() {
        val unknown = Exercise(
            id = 99L,
            name = "Unknown Exercise",
            primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL,
        )
        val source = UserCoefficientSource(emptyMap())
        assertNull(source.get(unknown))
    }
}
