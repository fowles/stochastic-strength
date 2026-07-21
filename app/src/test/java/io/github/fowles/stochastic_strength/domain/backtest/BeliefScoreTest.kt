package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Phase-2 score of the belief stack on real history vs the Phase-0 baseline (main's estimator:
 * total 26.7593 / per-set 0.12563 / 213 scored / 9 skipped). Skips without history.json.
 * NOTE: the belief stack may score sets main skipped (cold-start via siblings), which can only
 * ADD distance — comparing totals is conservative in main's favor.
 */
class BeliefScoreTest {

    @Test
    fun reportBeliefStackHeldOutScore() {
        val data = BacktestData.loadOrNull()
        Assume.assumeTrue("backtest/history.json not present; skipping", data != null)
        data!!

        val result = BeliefHeldOutScorer.score(data, BeliefConfig())
        val r = result.report
        assertTrue("belief stack must score real sets", r.scoredSets > 0)

        val baseline = BacktestData.baselineFile().takeIf { it.exists() }
            ?.let { JSONObject(it.readText()) }
        val sb = StringBuilder()
        sb.appendLine("=== Phase 2: belief stack held-out score (config = adopted defaults) ===")
        sb.appendLine("sets scored     : ${r.scoredSets} (skipped: ${r.skippedSets})")
        sb.appendLine("total distance  : ${"%.4f".format(r.totalDistance)} ln-units")
        sb.appendLine("mean per set    : ${"%.5f".format(r.totalDistance / r.scoredSets)} ln-units")
        sb.appendLine("coverage        : ${result.coveredSets}/${r.scoredSets} sets inside their interval")
        if (baseline != null) {
            sb.appendLine("main baseline   : total ${"%.4f".format(baseline.getDouble("totalDistance"))} / per-set ${"%.5f".format(baseline.getDouble("meanDistancePerSet"))} (${baseline.getInt("scoredSets")} sets)")
        }
        println(sb)

        // PHASE-2 SHIP GATE: beat main's Phase-0 baseline on PER-SET distance. Comparing totals is
        // invalid once the two stacks score different-sized histories (the belief stack cold-starts
        // more sets via siblings, and history.json grows over time); per-set mean is size-invariant.
        if (baseline != null) {
            val beliefPerSet = r.totalDistance / r.scoredSets
            assertTrue(
                "belief stack ($beliefPerSet/set) must beat main's baseline (${baseline.getDouble("meanDistancePerSet")}/set)",
                beliefPerSet < baseline.getDouble("meanDistancePerSet"),
            )
        }
    }
}
