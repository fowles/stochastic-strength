package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import org.junit.Assume
import org.junit.Test

/**
 * Runs the coordinate-descent fit of the belief stack's `fitted` constants on real history and
 * prints best config + per-axis sensitivity curves (constitution rule 2 admission evidence).
 * Human-gated: the printed values are adopted into BeliefConfig's defaults by hand, with the
 * curves recorded in the phase-2 plan. Skips without history.json.
 */
class BeliefFitTest {

    @Test
    fun fitBeliefConstantsOnRealHistory() {
        val data = BacktestData.loadOrNull()
        Assume.assumeTrue("backtest/history.json not present; skipping", data != null)
        data!!

        val result = BeliefFitHarness.fit(start = BeliefConfig()) { config ->
            BeliefHeldOutScorer.score(data, config).report.totalDistance
        }
        val sb = StringBuilder()
        sb.appendLine("=== Phase 2 fit: belief constants on real history (authority: held-out total) ===")
        sb.appendLine("best score : ${"%.4f".format(result.bestScore)} ln-units")
        sb.appendLine("best config: fatiguePerSetEstimate=${result.best.fatiguePerSetEstimate} qPerDay=${result.best.qPerDay} " +
            "sigmaObs=${result.best.sigmaObs} tau=${result.best.tau}")
        for ((axis, curve) in result.curves) {
            sb.appendLine("curve $axis : " + curve.joinToString("  ") { (v, s) -> "$v→${"%.4f".format(s)}" })
        }
        val cov = BeliefHeldOutScorer.score(data, result.best)
        sb.appendLine("coverage at best: ${cov.coveredSets}/${cov.report.scoredSets} " +
            "(skipped ${cov.report.skippedSets})")
        println(sb)
    }
}
