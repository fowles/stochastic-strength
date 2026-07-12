package io.github.fowles.stochastic_strength.domain.backtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class VarianceStudyScoringTest {

    @Test fun sweepFindsInteriorAndBoundaryOptima() {
        val interior = sweep(listOf(1.0, 2.0, 3.0)) { p -> -(p - 2.0) * (p - 2.0) } // peak at 2.0
        val v1 = interiorVerdict(interior)
        assertEquals(2.0, v1.bestParam, 1e-9)
        assertTrue(v1.interior)

        val boundary = sweep(listOf(1.0, 2.0, 3.0)) { p -> p } // peak at the top bound
        val v2 = interiorVerdict(boundary)
        assertEquals(3.0, v2.bestParam, 1e-9)
        assertFalse(v2.interior)
    }

    // Anchor: production's scoredReplayTotal is stable across runs. At sessionDayEffectSd=0 this would
    // equal captureStream+BaselineScorer (no day variance contribution). With sessionDayEffectSd=0.02
    // the production path marginalizes session day variance (MAP estimate, then cleanVar+day.variance
    // in the score) while BaselineScorer uses cleanVar+noiseSd² only — they legitimately diverge.
    // This test therefore pins the production total directly; the parity at sigmaDay=0 is covered by
    // dayEffectAtZeroSigmaEqualsBaseline (which checks DayEffectScorer(0)==BaselineScorer).
    // Re-pinned 2026-07-12: obsNoiseScale=1.0 + sessionDayEffectSd=0.02 (final day-effect-only adoption).
    @Test fun baselineScoringMatchesProductionScoredReplayTotal() {
        val data = BacktestHarness.load()
        assumeTrue("no personal history.json fixture; skipping", data != null)
        data!!
        val cfg = io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig()
        val prod = RecalibrationHarness.scoredReplayTotal(data.history, cfg, data::newSnapshot)
        // Pinned 2026-07-12 (obsNoiseScale=1.0, sessionDayEffectSd=0.02).
        assertEquals(-322.1649716142565, prod, 1.0)
    }

    @Test fun dayEffectAtZeroSigmaEqualsBaseline() {
        val cfg = io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig()
        val data = BacktestHarness.load()
        assumeTrue("no personal history.json fixture; skipping", data != null)
        data!!
        val stream = captureStream(data.history, cfg, data::newSnapshot)
        val base = heldOutScore(stream, BaselineScorer, minFold = 0)
        val day0 = heldOutScore(stream, DayEffectScorer(sigmaDay = 0f), minFold = 0)
        assertEquals(base, day0, 1e-6)
    }

    @Test fun dayEffectSharpensLaterSetsInSession() {
        // Two sets in one session, both far above prediction in the same direction: after learning a
        // positive day offset from set 1, set 2's score should be higher than the baseline (no-offset) score.
        val obs = io.github.fowles.stochastic_strength.domain.progression.SetObservation(
            lowerLn = null, upperLn = null, gaussianLn = 5.0f, noiseSd = 0.1f,
        )
        val s1 = ScoredSet(1L, 1L, null, 0L, 0, 1, obs, predMeanLn = 4.5f, cleanVar = 0.04f)
        val s2 = ScoredSet(1L, 2L, null, 0L, 0, 1, obs, predMeanLn = 4.5f, cleanVar = 0.04f)
        val baseline = BaselineScorer.sessionScore(listOf(s1, s2))
        val withDay = DayEffectScorer(sigmaDay = 0.15f).sessionScore(listOf(s1, s2))
        assertTrue(withDay > baseline)
    }
}
