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

    // Anchor: our independent capture+baseline scoring reproduces production's scored replay total.
    @Test fun baselineScoringMatchesProductionScoredReplayTotal() {
        val data = BacktestHarness.load()
        assumeTrue("no personal history.json fixture; skipping", data != null)
        data!!
        val cfg = io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig()
        val stream = captureStream(data.history, cfg, data::newSnapshot)
        val ours = heldOutScore(stream, BaselineScorer, minFold = 0)     // score ALL sessions
        val prod = RecalibrationHarness.scoredReplayTotal(data.history, cfg, data::newSnapshot)
        assertEquals(prod, ours, 1e-3)
    }
}
