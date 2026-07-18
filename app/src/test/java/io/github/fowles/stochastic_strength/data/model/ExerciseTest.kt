package io.github.fowles.stochastic_strength.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseTest {
    private fun ex(equipment: Equipment, isAsymmetric: Boolean) = Exercise(
        name = "X",
        primaryMuscle = MuscleGroup.BACK,
        equipment = equipment,
        isAsymmetric = isAsymmetric,
    )

    @Test
    fun `symmetric barbell uses bar plates`() {
        assertTrue(ex(Equipment.BARBELL, isAsymmetric = false).usesBarPlates)
    }

    @Test
    fun `asymmetric barbell does not use bar plates`() {
        assertFalse(ex(Equipment.BARBELL, isAsymmetric = true).usesBarPlates)
    }

    @Test
    fun `non-barbell never uses bar plates`() {
        assertFalse(ex(Equipment.DUMBBELL, isAsymmetric = false).usesBarPlates)
    }

    @Test
    fun `isAsymmetric defaults to false`() {
        assertFalse(
            Exercise(name = "X", primaryMuscle = MuscleGroup.BACK, equipment = Equipment.BARBELL)
                .isAsymmetric
        )
    }
}
