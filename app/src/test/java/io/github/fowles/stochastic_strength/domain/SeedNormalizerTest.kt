package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedNormalizerTest {

    private val normalizer = SeedNormalizer()

    private fun exercise(id: Long, name: String, muscle: MuscleGroup) = Exercise(
        id = id,
        name = name,
        primaryMuscle = muscle,
        equipment = Equipment.BARBELL,
    )

    private fun snapshot(id: Long, muscle: MuscleGroup, seed: Float, current: Float) =
        ExerciseCoefficientSnapshot(
            exercise = exercise(id, "Ex$id", muscle),
            seedCoefficient = seed,
            currentCoefficient = current,
        )

    private fun set(exerciseId: Long) = WorkoutSet(
        sessionId = 1L,
        exerciseId = exerciseId,
        setNumber = 1,
        targetWeight = 80f,
        targetReps = 5,
        feedback = SetFeedback.RIR_2_4,
    )

    @Test
    fun compute_returnsEmpty_whenNoSetsAndNoExercises() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = emptyList(),
            exercises = emptyList(),
            baselines = emptyMap(),
        ))
        assertTrue(out.isEmpty())
    }

    @Test
    fun compute_returnsEmpty_whenNoExercisesObserved() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = emptyList(),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.0f, current = 1.0f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.85f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        assertTrue(out.isEmpty())
    }

    @Test
    fun compute_skipsGroup_whenFewerThanTwoObservedQualifyingExercises() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.0f, current = 1.1f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.9f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        assertTrue(out.isEmpty())
    }

    @Test
    fun compute_dropsObservedExercisesWithZeroCurrentCoefficient() {
        // Two observed exercises but one has currentCoefficient = 0 -> only n=1 qualifies -> skip.
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.0f, current = 1.1f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.0f, current = 0.0f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        assertEquals(0, out.size)
    }
}
