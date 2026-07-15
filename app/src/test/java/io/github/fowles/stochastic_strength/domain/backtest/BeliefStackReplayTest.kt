package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.backtest.BacktestFixtures.DAY_MS
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefFold
import io.github.fowles.stochastic_strength.domain.belief.BeliefPooling
import io.github.fowles.stochastic_strength.domain.belief.EffectiveBelief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.ln

class BeliefStackReplayTest {

    private val config = BeliefConfig(
        sigmaSeed = 0.15f, sigmaOverride = 0.10f,
        phi = 0.05f, qPerDay = 1e-3f,
        sigmaObsRir = 0.10f, sigmaObsFail = 0.07f,
        tau = 0.10f, sigma2Floor = 4e-4f, sigma2Cap = 0.25f,
    )
    // Barbell Squat coef 1.00, Front Squat coef 0.80 — both QUADS (ExerciseCoefficients).
    private val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
    private val front = Exercise(id = 2, name = "Front Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)

    @Test
    fun predictionsArePreFoldPerSetAndFatigueAdjusted() {
        val sets = listOf(
            WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            WorkoutSet(id = 2, sessionId = 1, exerciseId = 1, setNumber = 2, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1),
        )
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS)),
            sets = sets,
            strengthOverrides = listOf(ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0)),
        ))
        val fold = BeliefFold(config)
        val pooling = BeliefPooling(config)

        var seen = listOf<BeliefStackReplay.SetPrediction>()
        var folded = mapOf<Long, Belief>()
        BeliefStackReplay.run(data, config) { _, _, predictions, _, beliefs ->
            seen = predictions; folded = beliefs.toMap()
        }

        // Hand-replay: seed belief, pool at asOf, per-set fatigue-shifted point predictions.
        val seedBeliefs = mapOf(1L to Belief(ln(110f), config.sigmaSeed * config.sigmaSeed, 0L))
        val snapshot = data.newSnapshot()
        val eff = pooling.effective(seedBeliefs, snapshot.seedCoefficients,
            snapshot.muscleExerciseIds[MuscleGroup.QUADS]!!, 1 * DAY_MS).effective[1L]!!
        assertEquals(2, seen.size)
        assertEquals(eff.mu - fold.fatigueShift(1), seen[0].predictedLn!!, 1e-5f)
        assertEquals(eff.mu - fold.fatigueShift(2), seen[1].predictedLn!!, 1e-5f)
        assertEquals(1, seen[0].rank); assertEquals(2, seen[1].rank)

        // Post-fold state matches foldSession on the aged seed.
        assertEquals(fold.foldSession(seedBeliefs[1L]!!, sets, 1 * DAY_MS), folded[1L])
    }

    @Test
    fun coldExerciseIsInitializedFromItsSiblingPredictionBeforeFolding() {
        // Session 1 trains the squat (seeded); session 2 trains the cold front squat.
        val sets1 = listOf(WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1))
        val sets2 = listOf(WorkoutSet(id = 2, sessionId = 2, exerciseId = 2, setNumber = 1, targetWeight = 70f, targetReps = 5, feedback = SetFeedback.RIR_0_1))
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat, front),
            sessions = listOf(
                WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS),
                WorkoutSession(id = 2, startTime = 0, endTime = 3 * DAY_MS),
            ),
            sets = sets1 + sets2,
            strengthOverrides = listOf(ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0)),
        ))
        var effAtSession2: EffectiveBelief? = null
        var beliefsAfter1: Map<Long, Belief> = emptyMap()
        var beliefsAfter2: Map<Long, Belief> = emptyMap()
        BeliefStackReplay.run(data, config) { sessionId, _, _, effective, beliefs ->
            if (sessionId == 1L) beliefsAfter1 = beliefs.toMap()
            if (sessionId == 2L) { effAtSession2 = effective[2L]; beliefsAfter2 = beliefs.toMap() }
        }
        assertNull(beliefsAfter1[2L])   // not materialized before it is trained
        // The cold prior IS the pre-fold sibling prediction; folding it yields the stored belief.
        val fold = BeliefFold(config)
        val expected = fold.foldSession(
            Belief(effAtSession2!!.mu, effAtSession2!!.sigma2, 3 * DAY_MS), sets2, 3 * DAY_MS)
        assertEquals(expected, beliefsAfter2[2L])
    }
}
