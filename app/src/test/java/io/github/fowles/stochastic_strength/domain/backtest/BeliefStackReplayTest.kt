package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.backtest.BacktestFixtures.DAY_MS
import io.github.fowles.stochastic_strength.domain.CoefficientCompression
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefFold
import io.github.fowles.stochastic_strength.domain.belief.BeliefPooling
import io.github.fowles.stochastic_strength.domain.belief.EffectiveBelief
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class BeliefStackReplayTest {

    private val config = BeliefConfig(
        sigmaSeed = 0.15f, sigmaOverride = 0.10f,
        fatiguePerSetEstimate = 0.05f, confidenceDecayEstimate = 1e-3f,
        perSetDoubtEstimate = 0.10f,
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
            // squat coef 1.00: a QUADS baseline of 110 seeds exercise 1 at 110 (only squat is
            // loaded here, so the muscle-wide expansion produces exactly this one seed).
            baselineOverrides = listOf(BaselineOverride(sessionId = null, muscleGroup = MuscleGroup.QUADS, baselineWeight = 110f, asOf = 0)),
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
    fun newlyTrainedExerciseUsesItsMuscleSeedThenFolds() {
        // Live seed expansion seeds EVERY loaded QUADS exercise from the muscle baseline up front —
        // front squat (coef 0.80) is never "cold": it gets its own initial belief at ln(110 * 0.80)
        // just like squat gets ln(110 * 1.00). Session 1 trains only the squat; session 2 trains the
        // (still-untouched, now stale) front squat for the first time.
        val sets1 = listOf(WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1))
        val sets2 = listOf(WorkoutSet(id = 2, sessionId = 2, exerciseId = 2, setNumber = 1, targetWeight = 70f, targetReps = 5, feedback = SetFeedback.RIR_0_1))
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat, front),
            sessions = listOf(
                WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS),
                WorkoutSession(id = 2, startTime = 0, endTime = 3 * DAY_MS),
            ),
            sets = sets1 + sets2,
            baselineOverrides = listOf(BaselineOverride(sessionId = null, muscleGroup = MuscleGroup.QUADS, baselineWeight = 110f, asOf = 0)),
        ))
        var effAtSession2: EffectiveBelief? = null
        var beliefsAfter1: Map<Long, Belief> = emptyMap()
        var beliefsAfter2: Map<Long, Belief> = emptyMap()
        BeliefStackReplay.run(data, config) { sessionId, _, _, effective, beliefs ->
            if (sessionId == 1L) beliefsAfter1 = beliefs.toMap()
            if (sessionId == 2L) { effAtSession2 = effective[2L]; beliefsAfter2 = beliefs.toMap() }
        }
        // Front squat IS seeded from session start — untouched by session 1 (which only trains
        // squat), so its belief after session 1 is exactly its initial muscle-baseline seed.
        val frontSeed = beliefsAfter1.getValue(2L)
        val frontCoef = CoefficientCompression.compress(0.80f, ExerciseCoefficients.LAMBDA)
        assertEquals(ln(110f * frontCoef), frontSeed.mu, 1e-5f)
        assertEquals(config.sigmaSeed * config.sigmaSeed, frontSeed.sigma2, 1e-9f)
        assertEquals(0L, frontSeed.updatedAt)

        // Front squat HAS its own seeded belief (unlike the old cold-exercise case), so the actual
        // fold ages and folds ITS OWN belief directly — the fold is local (CLAUDE.md: "cross-informing
        // happens only at read time"). Pre-fold pooling's blended `mu`/`sigma2` is a read-time-only
        // prediction (used for scoring/trace); the breakdown's `own` field is what the engine
        // actually folds against, and it's exposed precisely so consumers never re-derive the math.
        val fold = BeliefFold(config)
        val ownAged = effAtSession2!!.own!!
        assertEquals(0.4677618f, effAtSession2!!.siblingShare, 1e-4f) // sanity: sibling meaningfully informs the blend
        val expected = fold.foldSession(ownAged, sets2, 3 * DAY_MS)
        assertEquals(expected, beliefsAfter2[2L])
    }
}
