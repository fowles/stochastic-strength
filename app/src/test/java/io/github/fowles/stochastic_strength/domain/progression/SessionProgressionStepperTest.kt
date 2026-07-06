package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class SessionProgressionStepperTest {

    private val stepper = SessionProgressionStepper()

    private fun snapshot(): ReplaySnapshot {
        // Two exercises in CHEST, both loaded (seed > 0).
        val snap = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(1L to 1.0f, 2L to 0.6f),
        )
        snap.currentEstimates[1L] = ExerciseEstimate(lnE = ln(100f), confidence = 6f, updatedAt = 0L)
        snap.currentEstimates[2L] = ExerciseEstimate(lnE = ln(60f), confidence = 6f, updatedAt = 0L)
        return snap
    }

    private fun set(exerciseId: Long, weight: Float, reps: Int, feedback: SetFeedback) = WorkoutSet(
        sessionId = 10L,
        exerciseId = exerciseId,
        setNumber = 1,
        targetWeight = weight,
        targetReps = reps,
        actualReps = reps,
        feedback = feedback,
    )

    @Test
    fun foldMovesOnlyTheWorkedExerciseAndReturnsItsMuscle() {
        val snap = snapshot()
        val before2 = snap.currentEstimates.getValue(2L).lnE
        val result = stepper.step(
            sets = listOf(set(1L, weight = 105f, reps = 5, feedback = SetFeedback.RIR_2_4)),
            snapshot = snap,
            asOf = 1_000L,
        )
        // Exercise 1 moved; exercise 2 untouched (local fold).
        assertTrue(snap.currentEstimates.getValue(1L).updatedAt == 1_000L)
        assertEquals(before2, snap.currentEstimates.getValue(2L).lnE, 1e-6f)
        // The worked exercise's muscle is reported with a projection.
        assertEquals(1, result.steps.size)
        assertEquals(MuscleGroup.CHEST, result.steps.first().muscle)
        assertTrue(result.steps.first().projection.level > 0f)
    }

    @Test
    fun hurtLeavesEstimatesUntouched() {
        val snap = snapshot()
        val before1 = snap.currentEstimates.getValue(1L).lnE
        val before2 = snap.currentEstimates.getValue(2L).lnE
        val result = stepper.step(
            sets = listOf(set(2L, weight = 60f, reps = 5, feedback = SetFeedback.HURT)),
            snapshot = snap,
            asOf = 2_000L,
        )
        // Pain is a policy concern (PrescriptionPolicy.hurtMultiplier); capacity history stays intact.
        assertEquals(before1, snap.currentEstimates.getValue(1L).lnE, 1e-6f)
        assertEquals(before2, snap.currentEstimates.getValue(2L).lnE, 1e-6f)
        assertTrue("hurt-only session emits no projection step", result.steps.isEmpty())
    }
}
