package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.CoefficientSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseSeedExpansionTest {

    private fun ex(id: Long, muscle: MuscleGroup) = Exercise(
        id = id, name = "ex$id", primaryMuscle = muscle, secondaryMuscles = emptyList(),
        equipment = Equipment.BARBELL, isDisliked = false, isUnilateral = false, isTimed = false,
    )

    private val coef = object : CoefficientSource {
        override fun get(exercise: Exercise): Float? = when (exercise.id) {
            1L -> 1.0f; 2L -> 0.6f; 3L -> 0.0f; else -> null
        }
    }

    @Test
    fun expandsOneMuscleRowIntoPerExerciseRows() {
        val rows = ExerciseSeedExpansion.expand(
            muscleOverrides = listOf(
                ExerciseSeedExpansion.MuscleOverrideRow(null, MuscleGroup.CHEST, 80f, 0L, BaselineChangeReason.INITIAL),
            ),
            exercises = listOf(ex(1L, MuscleGroup.CHEST), ex(2L, MuscleGroup.CHEST), ex(3L, MuscleGroup.CHEST)),
            coefSource = coef,
        )
        // Loaded chest exercises 1 (coef 1.0) and 2 (coef 0.6) get rows; 3 (coef 0) is skipped.
        assertEquals(2, rows.size)
        assertEquals(80f, rows.first { it.exerciseId == 1L }.e1rm, 1e-3f)
        assertEquals(48f, rows.first { it.exerciseId == 2L }.e1rm, 1e-3f)
        assertTrue("zero-coef exercise excluded", rows.none { it.exerciseId == 3L })
    }

    @Test
    fun preservesSessionAsOfAndReason() {
        val rows = ExerciseSeedExpansion.expand(
            muscleOverrides = listOf(
                ExerciseSeedExpansion.MuscleOverrideRow(7L, MuscleGroup.CHEST, 90f, 1234L, BaselineChangeReason.DETRAIN),
            ),
            exercises = listOf(ex(1L, MuscleGroup.CHEST)),
            coefSource = coef,
        )
        assertEquals(1, rows.size)
        assertEquals(7L, rows[0].sessionId)
        assertEquals(1234L, rows[0].asOf)
        assertEquals(BaselineChangeReason.DETRAIN, rows[0].reason)
    }
}
