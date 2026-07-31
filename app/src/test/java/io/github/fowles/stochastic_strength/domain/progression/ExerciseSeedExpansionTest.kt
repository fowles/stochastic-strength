package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseSeedExpansionTest {

    // Two CHEST exercises (coef 1.0 and 0.5), one BACK (coef 1.0), one bodyweight CHEST (coef 0.0).
    private val exerciseMuscle = mapOf(
        1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST, 3L to MuscleGroup.BACK, 4L to MuscleGroup.CHEST,
    )
    private val coefById = mapOf(1L to 1.0f, 2L to 0.5f, 3L to 1.0f, 4L to 0.0f)

    @Test
    fun expand_scalesEachLoadedExerciseByCoef_andSkipsZeroCoef() {
        val rows = ExerciseSeedExpansion.expand(
            muscleBaselines = listOf(
                ExerciseSeedExpansion.MuscleBaseline(null, MuscleGroup.CHEST, 80f, 0L),
            ),
            exerciseMuscle = exerciseMuscle,
            coefById = coefById,
        )
        // exercise 4 (coef 0) is skipped; 1 -> 80, 2 -> 40. No BACK row (no BACK baseline given).
        assertEquals(setOf(1L to 80f, 2L to 40f), rows.map { it.exerciseId to it.e1rm }.toSet())
        assertTrue(rows.all { it.sessionId == null && it.asOf == 0L })
    }

    @Test
    fun expand_skipsRowsWhoseProductIsNotPositive() {
        val rows = ExerciseSeedExpansion.expand(
            muscleBaselines = listOf(ExerciseSeedExpansion.MuscleBaseline(null, MuscleGroup.CHEST, 0f, 0L)),
            exerciseMuscle = exerciseMuscle,
            coefById = coefById,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun buildSeeds_defaultsMusclesWithoutAnInitialOverrideToStartingWeights() {
        // No overrides at all -> every muscle uses StartingWeights.baseline(sex, level, muscle).
        val seeds = ExerciseSeedExpansion.buildSeeds(
            initialOverrides = emptyList(),
            sessionOverrides = emptyList(),
            sex = Sex.MALE, level = StrengthLevel.MEDIUM,
            exerciseMuscle = exerciseMuscle,
            coefById = coefById,
        )
        // CHEST default = 80 (StartingWeights), BACK default = 80. Exercise 1 -> 80, 2 -> 40, 3 -> 80.
        assertEquals(setOf(1L to 80f, 2L to 40f, 3L to 80f), seeds.initial.map { it.exerciseId to it.e1rm }.toSet())
        assertTrue(seeds.bySession.isEmpty())
    }

    @Test
    fun buildSeeds_prefersInitialOverrideOverDefault_perMuscle() {
        val seeds = ExerciseSeedExpansion.buildSeeds(
            initialOverrides = listOf(
                BaselineOverride(sessionId = null, muscleGroup = MuscleGroup.CHEST, baselineWeight = 100f, asOf = 5L, reason = BaselineChangeReason.INITIAL),
            ),
            sessionOverrides = emptyList(),
            sex = Sex.MALE, level = StrengthLevel.MEDIUM,
            exerciseMuscle = exerciseMuscle,
            coefById = coefById,
        )
        // CHEST overridden to 100 (asOf 5) -> 1:100, 2:50; BACK still default 80 -> 3:80.
        assertEquals(mapOf(1L to 100f, 2L to 50f, 3L to 80f), seeds.initial.associate { it.exerciseId to it.e1rm })
        assertEquals(5L, seeds.initial.first { it.exerciseId == 1L }.asOf)
    }

    @Test
    fun buildSeeds_routesSessionScopedOverridesIntoBySession() {
        val seeds = ExerciseSeedExpansion.buildSeeds(
            initialOverrides = emptyList(),
            sessionOverrides = listOf(
                BaselineOverride(sessionId = 7L, muscleGroup = MuscleGroup.CHEST, baselineWeight = 90f, asOf = 123L, reason = BaselineChangeReason.OVERRIDE),
            ),
            sex = Sex.MALE, level = StrengthLevel.MEDIUM,
            exerciseMuscle = exerciseMuscle,
            coefById = coefById,
        )
        assertEquals(setOf(1L to 90f, 2L to 45f), seeds.bySession.getValue(7L).map { it.exerciseId to it.e1rm }.toSet())
        assertTrue(seeds.bySession.getValue(7L).all { it.asOf == 123L })
    }
}
