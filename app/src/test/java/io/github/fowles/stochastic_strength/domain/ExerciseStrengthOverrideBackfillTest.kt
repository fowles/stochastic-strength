package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.progression.ExerciseSeedExpansion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseStrengthOverrideBackfillTest {

    private fun ex(id: Long, name: String, muscle: MuscleGroup) = Exercise(
        id = id, name = name, primaryMuscle = muscle, secondaryMuscles = emptyList(),
        equipment = Equipment.BARBELL, isDisliked = false, isUnilateral = false, isTimed = false,
    )

    @Test
    fun skipsWhenAlreadyDone() {
        val rows = planBackfill(
            alreadyDone = true,
            muscleOverrides = listOf(
                ExerciseSeedExpansion.MuscleOverrideRow(null, MuscleGroup.CHEST, 80f, 0L, BaselineChangeReason.INITIAL),
            ),
            exercises = listOf(ex(1L, "Barbell Bench Press", MuscleGroup.CHEST)),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun expandsUsingRealSeedCoefficients() {
        // "Barbell Bench Press" seed coef is 1.0 in ExerciseCoefficients.
        val rows = planBackfill(
            alreadyDone = false,
            muscleOverrides = listOf(
                ExerciseSeedExpansion.MuscleOverrideRow(null, MuscleGroup.CHEST, 80f, 0L, BaselineChangeReason.INITIAL),
            ),
            exercises = listOf(ex(1L, "Barbell Bench Press", MuscleGroup.CHEST)),
        )
        assertEquals(1, rows.size)
        assertEquals(80f, rows[0].e1rm, 1e-3f)
    }
}
