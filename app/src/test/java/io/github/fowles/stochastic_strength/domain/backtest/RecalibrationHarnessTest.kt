package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import io.github.fowles.stochastic_strength.domain.progression.ReplayHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecalibrationHarnessTest {

    private fun session(id: Long, end: Long) =
        WorkoutSession(id = id, startTime = end - 1000, endTime = end)

    // Carries RIR_2_4 feedback so SetObservation.from yields a scorable censored observation
    // (needs only targetWeight/targetReps), driving real predictive log-scores through the accumulator.
    private fun set(id: Long, sessionId: Long) =
        WorkoutSet(
            id = id, sessionId = sessionId, exerciseId = 1L, setNumber = 1,
            targetWeight = 20f, targetReps = 10, actualReps = 10, feedback = SetFeedback.RIR_2_4,
        )

    private fun history(n: Int): ReplayHistory {
        val sessions = (1..n).map { session(it.toLong(), end = it * 1000L) }
        val sets = (1..n).associate { it.toLong() to listOf(set(it.toLong(), it.toLong())) }
        // Seed exercise 1's belief (as the production replay does from its initial override rows);
        // the stepper only folds/scores exercises that already have a belief.
        val initialOverrides = listOf(
            ExerciseStrengthOverride(id = 1, sessionId = null, exerciseId = 1L, e1rm = 25f, asOf = 0L),
        )
        return ReplayHistory(
            sessions = sessions,
            setsBySession = sets,
            initialOverrides = initialOverrides,
            sessionOverrides = emptyMap(),
        )
    }

    private fun emptySnapshot() = ReplaySnapshot(
        exerciseMuscle = mapOf(1L to MuscleGroup.CHEST),
        seedCoefficients = mapOf(1L to 1.0f),
        exerciseEquipment = emptyMap(),
    )

    @Test
    fun truncateTo_keepsFirstKSessionsAndTheirSets() {
        val h = history(5)
        val t = RecalibrationHarness.truncateTo(h, 3)
        assertEquals(listOf(1L, 2L, 3L), t.sessions.map { it.id })
        assertEquals(setOf(1L, 2L, 3L), t.setsBySession.keys)
    }

    @Test
    fun foldScores_enumeratesFoldsAndDefaultMatchesProposedForIdentityFit() {
        val user = RecalibrationHarness.UserHistory(history(6)) { emptySnapshot() }
        // Identity fit: always return defaults -> proposed == default per fold.
        val rows = RecalibrationHarness.foldScores(user, minFoldSessions = 3) { EstimatorConfig() }
        // Folds k = 3,4,5 (k .. N-1, N=6)
        assertEquals(listOf(3, 4, 5), rows.map { it.k })
        rows.forEach { assertEquals(it.heldOutDefault, it.heldOutProposed, 1e-9) }
    }

    @Test
    fun scoredReplayTotalAndHeldOutCarryRealNonZeroSignal() {
        val h = history(6)
        // A scored replay over feedback-carrying sets must produce a finite, non-zero predictive total.
        val total = RecalibrationHarness.scoredReplayTotal(
            RecalibrationHarness.truncateTo(h, 4), EstimatorConfig(), ::emptySnapshot,
        )
        assertTrue("scored replay total must be finite", total.isFinite())
        assertNotEquals("scored replay total must be non-zero", 0.0, total, 1e-9)

        // At least one fold's held-out score (a difference of two scored replays) must be non-zero,
        // proving the differencing actually captures session k+1's predictive contribution.
        val user = RecalibrationHarness.UserHistory(h) { emptySnapshot() }
        val rows = RecalibrationHarness.foldScores(user, minFoldSessions = 3) { EstimatorConfig() }
        assertTrue(
            "at least one held-out default score must be non-zero",
            rows.any { kotlin.math.abs(it.heldOutDefault) > 1e-9 },
        )
    }

    @Test
    fun classify_flagsStablePinnedAndFragileTrajectories() {
        val lo = 1.0 / 16.0
        val hi = 16.0
        // Tight around 6.0 -> STABLE
        assertEquals(
            RecalibrationHarness.Flag.STABLE,
            RecalibrationHarness.classify(listOf(5.9, 6.0, 6.1, 6.0), lo, hi),
        )
        // Sitting at the upper bound -> PINS_BOUND
        assertEquals(
            RecalibrationHarness.Flag.PINS_BOUND,
            RecalibrationHarness.classify(listOf(16.0, 16.0, 15.99, 16.0), lo, hi),
        )
        // All over the place -> FRAGILE
        assertEquals(
            RecalibrationHarness.Flag.FRAGILE,
            RecalibrationHarness.classify(listOf(0.5, 3.0, 1.0, 9.0), lo, hi),
        )
    }

    @Test
    fun assemble_producesFourVerdictsAndCvTotals() {
        val user = RecalibrationHarness.UserHistory(history(6)) { emptySnapshot() }
        val rows = RecalibrationHarness.foldScores(user, minFoldSessions = 3) { EstimatorConfig() }
        val report = RecalibrationHarness.assemble(rows, 1.0 / 16.0, 16.0)
        assertEquals(4, report.params.size)
        assertEquals(listOf("drift", "fatigue", "procNoise", "tau"), report.params.map { it.name })
        // Identity fit -> proposed multipliers all 1.0, CV delta ~0.
        report.params.forEach { assertEquals(1.0, it.proposedMultiplier, 1e-9) }
        assertEquals(0.0, report.cvTotalProposed - report.cvTotalDefault, 1e-9)
    }
}
