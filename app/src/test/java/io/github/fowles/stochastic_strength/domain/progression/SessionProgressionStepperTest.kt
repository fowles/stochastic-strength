package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProgressionStepperTest {
    private val config = EstimatorConfig()
    private val stepper = SessionProgressionStepper()
    private fun snapshot() = ReplaySnapshot(
        exerciseMuscle = mapOf(1L to MuscleGroup.QUADS, 2L to MuscleGroup.QUADS, 3L to MuscleGroup.CHEST),
        seedCoefficients = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 1.0f),
    ).apply {
        currentBeliefs[1L] = ExerciseBelief.seed(100f, 0L, config)
        currentBeliefs[2L] = ExerciseBelief.seed(80f, 0L, config)
        currentBeliefs[3L] = ExerciseBelief.seed(60f, 0L, config)
    }
    private fun set(ex: Long, n: Int, fb: SetFeedback?, w: Float = 70f, reps: Int = 10, actual: Int? = null) =
        WorkoutSet(sessionId = 1, exerciseId = ex, setNumber = n, targetWeight = w, targetReps = reps, actualReps = actual, feedback = fb)

    @Test
    fun setsFoldSequentiallyAndTightenTheBelief() {
        val snap = snapshot()
        val before = snap.currentBeliefs.getValue(1L)
        stepper.step(listOf(set(1L, 1, SetFeedback.RIR_0_1), set(1L, 2, SetFeedback.RIR_0_1), set(1L, 3, SetFeedback.RIR_0_1)), snap, asOf = 1000L)
        val after = snap.currentBeliefs.getValue(1L)
        assertTrue("3 in-target sets must tighten sigma", after.sigma2 < before.sigma2)
        assertEquals(1000L, after.updatedAt)
    }

    @Test
    fun failureLowersTheBeliefMean() {
        // 5 reps at 70 kg implies rawToOneRepMax(70, 5.5) ≈ 85.5 kg < seed (100 kg) → pulls mu down.
        val snap = snapshot()
        val before = snap.currentBeliefs.getValue(1L).mu
        stepper.step(listOf(set(1L, 1, SetFeedback.TOO_HARD, w = 70f, actual = 5)), snap, 1000L)
        assertTrue(snap.currentBeliefs.getValue(1L).mu < before)
    }

    @Test
    fun hurtOnlySessionsFoldNothingAndDoNotTouchTheMuscleClock() {
        val snap = snapshot()
        val before = snap.currentBeliefs.getValue(1L)
        val result = stepper.step(listOf(set(1L, 1, SetFeedback.HURT)), snap, 1000L)
        assertEquals(before, snap.currentBeliefs.getValue(1L))
        assertTrue(result.steps.isEmpty())
        assertTrue(snap.muscleLastObs.isEmpty())
    }

    @Test
    fun zeroCoefficientExercisesAreSkipped() {
        val snap = snapshot()
        snap.currentBeliefs[9L] = ExerciseBelief.seed(50f, 0L, config)
        val result = stepper.step(listOf(set(9L, 1, SetFeedback.RIR_0_1)), snap, 1000L)
        assertTrue(result.steps.isEmpty())
        // The skip must reach the fold, not just the projection: the belief stays at its seed.
        assertEquals(ExerciseBelief.seed(50f, 0L, config), snap.currentBeliefs.getValue(9L))
    }

    @Test
    fun muscleClockAdvancesOnlyForFoldedMuscles() {
        val snap = snapshot()
        stepper.step(listOf(set(1L, 1, SetFeedback.RIR_2_4), set(3L, 1, SetFeedback.HURT)), snap, 1000L)
        assertEquals(1000L, snap.muscleLastObs[MuscleGroup.QUADS])
        assertTrue(MuscleGroup.CHEST !in snap.muscleLastObs)
    }

    @Test
    fun laterSetsCountAsMoreFatigued() {
        // The same failed set folded at rank 3 implies MORE fresh capacity than at rank 1.
        val s1 = snapshot(); val s3 = snapshot()
        stepper.step(listOf(set(1L, 1, SetFeedback.TOO_HARD, w = 90f, actual = 5)), s1, 1000L)
        stepper.step(
            listOf(set(1L, 1, null, w = 90f), set(1L, 2, null, w = 90f), set(1L, 3, SetFeedback.TOO_HARD, w = 90f, actual = 5)),
            s3, 1000L,
        )
        assertTrue(s3.currentBeliefs.getValue(1L).mu > s1.currentBeliefs.getValue(1L).mu)
    }

    @Test
    fun projectionStepsAreEmittedPerAffectedMuscle() {
        val snap = snapshot()
        val result = stepper.step(listOf(set(1L, 1, SetFeedback.RIR_0_1), set(3L, 1, SetFeedback.RIR_0_1, w = 40f)), snap, 1000L)
        assertEquals(setOf(MuscleGroup.QUADS, MuscleGroup.CHEST), result.steps.map { it.muscle }.toSet())
    }
}
