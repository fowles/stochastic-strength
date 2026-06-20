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

    @Test
    fun compute_returnsMNearOne_whenCoefficientsMatchSeeds() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.0f, current = 1.0f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.85f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        assertEquals(1, out.size)
        assertEquals(MuscleGroup.CHEST, out[0].muscleGroup)
        assertEquals(1.0f, out[0].scale, 1e-4f)
    }

    @Test
    fun compute_returnsMLessThanOne_whenCoefficientsDriftedAboveSeed() {
        // c > s everywhere -> Σ(c·s) < Σ(c²) -> m < 1 -> scaling coefficients DOWN toward seed,
        // baseline = old / m > old (the system thinks the user got stronger).
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.0f, current = 1.10f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.95f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        assertEquals(1, out.size)
        assertTrue("m should be < 1, got ${out[0].scale}", out[0].scale < 1f)
    }

    @Test
    fun compute_returnsMGreaterThanOne_whenCoefficientsDriftedBelowSeed() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.0f, current = 0.90f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.75f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        assertEquals(1, out.size)
        assertTrue("m should be > 1, got ${out[0].scale}", out[0].scale > 1f)
    }

    @Test
    fun compute_solvesLeastSquaresOptimally_handComputed() {
        // c = [1.10, 0.95], s = [1.00, 0.85]
        // num = 1.10*1.00 + 0.95*0.85 = 1.10 + 0.8075 = 1.9075
        // den = 1.10^2 + 0.95^2 = 1.21 + 0.9025 = 2.1125
        // m = 1.9075 / 2.1125 ≈ 0.9029586
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.00f, current = 1.10f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.95f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        assertEquals(1, out.size)
        assertEquals(0.9029586f, out[0].scale, 1e-4f)
    }

    @Test
    fun compute_handlesMuscleGroupsIndependently() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L), set(3L), set(4L)),
            exercises = listOf(
                // CHEST: drifted up
                snapshot(1L, MuscleGroup.CHEST, seed = 1.0f, current = 1.10f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.95f),
                // BACK: drifted down
                snapshot(3L, MuscleGroup.BACK, seed = 1.0f, current = 0.90f),
                snapshot(4L, MuscleGroup.BACK, seed = 0.60f, current = 0.50f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f, MuscleGroup.BACK to 80f),
        ))
        assertEquals(2, out.size)
        val byMuscle = out.associateBy { it.muscleGroup }
        assertTrue(byMuscle.getValue(MuscleGroup.CHEST).scale < 1f)
        assertTrue(byMuscle.getValue(MuscleGroup.BACK).scale > 1f)
    }

    @Test
    fun compute_metadataContainsNAndMAndRmseBeforeAndAfter() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.00f, current = 1.10f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.95f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        val meta = out.single().metadata
        assertTrue("metadata should be non-null", meta != null)
        assertTrue("metadata should contain n=2: $meta", meta!!.contains("n=2"))
        assertTrue("metadata should contain m=: $meta", meta.contains("m="))
        assertTrue("metadata should contain rmse_before=: $meta", meta.contains("rmse_before="))
        assertTrue("metadata should contain rmse_after=: $meta", meta.contains("rmse_after="))
    }

    @Test
    fun compute_metadataRmseAfterIsLowerThanRmseBefore_whenDriftExists() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.00f, current = 1.10f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.95f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        val meta = out.single().metadata!!
        val before = Regex("rmse_before=([0-9.]+)").find(meta)!!.groupValues[1].toFloat()
        val after = Regex("rmse_after=([0-9.]+)").find(meta)!!.groupValues[1].toFloat()
        assertTrue("after $after should be < before $before", after < before)
    }
}
