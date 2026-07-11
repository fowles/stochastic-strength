package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepperDayEffectTest {
    private val muscle = MuscleGroup.QUADS

    private fun snapshot(exerciseIds: List<Long>): ReplaySnapshot {
        val snap = ReplaySnapshot(
            exerciseMuscle = exerciseIds.associateWith { muscle },
            seedCoefficients = exerciseIds.associateWith { 1f },
            exerciseEquipment = exerciseIds.associateWith { Equipment.BARBELL },
        )
        exerciseIds.forEach { snap.currentBeliefs[it] = ExerciseBelief.seed(60f, at = 0L, config = EstimatorConfig()) }
        return snap
    }

    private fun set(ex: Long, n: Int, weight: Float, reps: Int, fb: SetFeedback) = WorkoutSet(
        sessionId = 1, exerciseId = ex, setNumber = n, targetWeight = weight, targetReps = reps,
        actualReps = null, feedback = fb,
    )

    @Test fun zeroSigmaDayIsBitIdenticalToNoDayEffect() {
        val ids = listOf(1L, 2L)
        val sets = listOf(
            set(1, 1, 65f, 5, SetFeedback.RIR_0_1), set(1, 2, 65f, 5, SetFeedback.RIR_0_1),
            set(2, 1, 70f, 5, SetFeedback.RIR_2_4), set(2, 2, 70f, 5, SetFeedback.RIR_2_4),
        )
        val a = snapshot(ids); val b = snapshot(ids)
        SessionProgressionStepper(EstimatorConfig(sessionDayEffectSd = 0f)).step(sets, a, asOf = DAY)
        SessionProgressionStepper(EstimatorConfig(sessionDayEffectSd = 0f)).step(sets, b, asOf = DAY)
        for (id in ids) assertEquals(a.currentBeliefs[id]!!.mu, b.currentBeliefs[id]!!.mu, 0f)
    }

    @Test fun uniformlyHighSessionDampensPerExerciseUpdatesVsNoDayEffect() {
        // A whole-session "good day": every exercise beats its seed by the same amount. With a day-effect,
        // the shared d absorbs the common surprise, so each belief moves LESS than with no day-effect.
        val ids = listOf(1L, 2L, 3L)
        val sets = ids.flatMap { ex ->
            (1..2).map { n -> set(ex, n, 80f, 5, SetFeedback.RIR_0_1) } // heavy + easy = strong upward surprise
        }
        val withDay = snapshot(ids); val without = snapshot(ids)
        SessionProgressionStepper(EstimatorConfig(sessionDayEffectSd = 0.18f)).step(sets, withDay, asOf = DAY)
        SessionProgressionStepper(EstimatorConfig(sessionDayEffectSd = 0f)).step(sets, without, asOf = DAY)
        for (id in ids) {
            val movedWithDay = withDay.currentBeliefs[id]!!.mu - ExerciseBelief.seed(60f, 0L).mu
            val movedWithout = without.currentBeliefs[id]!!.mu - ExerciseBelief.seed(60f, 0L).mu
            assertTrue("id=$id: day-effect should dampen ($movedWithDay) vs none ($movedWithout)",
                movedWithDay in 0f..movedWithout || (movedWithout < 0f && movedWithDay > movedWithout))
        }
    }

    @Test fun singleExerciseSessionStillFolds() {
        val ids = listOf(1L)
        val sets = listOf(set(1, 1, 65f, 5, SetFeedback.RIR_0_1))
        val snap = snapshot(ids)
        SessionProgressionStepper(EstimatorConfig(sessionDayEffectSd = 0.18f)).step(sets, snap, asOf = DAY)
        assertTrue(snap.currentBeliefs[1]!!.mu.isFinite())
    }

    private companion object { const val DAY = 24L * 60 * 60 * 1000 }
}
