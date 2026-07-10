package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepperScoringTest {
    private fun snapshot(): ReplaySnapshot {
        val snap = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.QUADS),
            seedCoefficients = mapOf(1L to 1.0f),
            exerciseEquipment = mapOf(1L to Equipment.BARBELL),
        )
        snap.currentBeliefs[1L] = ExerciseBelief.seed(100f, at = 0L)
        return snap
    }

    private fun set(reps: Int, fb: SetFeedback) = WorkoutSet(
        sessionId = 1L, exerciseId = 1L, setNumber = 1, targetWeight = 80f,
        targetReps = reps, actualReps = null, feedback = fb,
    )

    @Test fun accumulatorSumsOneScorePerLoadObservation() {
        val acc = PredictiveScoreAccumulator()
        val stepper = SessionProgressionStepper(scorer = acc)
        val snap = snapshot()
        stepper.step(listOf(set(8, SetFeedback.RIR_2_4)), snap, asOf = 1_000L)
        // One load-bearing set → exactly one finite score contribution.
        assertTrue(acc.total.isFinite())
        val before = acc.total
        stepper.step(listOf(set(8, SetFeedback.HURT)), snapshot(), asOf = 2_000L)
        // HURT carries no observation → no additional score beyond the first accumulator's state.
        assertEquals(before, acc.total, 1e-9) // acc unchanged: HURT contributes nothing
    }

    @Test fun productionPathWithoutScorerIsUnaffected() {
        val snap = snapshot()
        val before = snap.currentBeliefs[1L]!!.mu
        SessionProgressionStepper().step(listOf(set(8, SetFeedback.RIR_2_4)), snap, asOf = 1_000L)
        assertTrue(snap.currentBeliefs[1L]!!.mu != before) // fold still happened, no scorer needed
    }
}
