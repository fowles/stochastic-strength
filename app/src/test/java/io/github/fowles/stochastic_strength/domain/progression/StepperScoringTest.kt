package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // Regression guard for the C1 defect (whole-branch review 2026-07-10): the stepper's [config]
    // MUST reach the belief folds (the BeliefUpdater), not just SetObservation's fatigue — otherwise
    // the fitter is silently blind to processNoise/detrain/τ. A history with a time gap makes
    // processNoisePerDay affect the aged predictive variance, so two configs differing ONLY in
    // processNoise must produce different predictive scores.
    @Test fun nonFatigueConfigParamReachesTheFolds() {
        fun scoreWith(config: EstimatorConfig): Double {
            val acc = PredictiveScoreAccumulator()
            val stepper = SessionProgressionStepper(config = config, scorer = acc)
            val snap = snapshot()
            stepper.step(listOf(set(8, SetFeedback.RIR_2_4)), snap, asOf = 0L)
            stepper.step(listOf(set(8, SetFeedback.RIR_2_4)), snap, asOf = 60L * 24 * 60 * 60 * 1000L)
            return acc.total
        }
        val base = EstimatorConfig()
        val hiProc = base.copy(processNoisePerDay = base.processNoisePerDay * 4f)
        assertNotEquals(scoreWith(base), scoreWith(hiProc), 1e-9)
    }
}
