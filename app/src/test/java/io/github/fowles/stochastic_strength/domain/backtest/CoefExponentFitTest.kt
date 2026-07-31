package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import org.junit.Assume
import org.junit.Test

/**
 * 1-D held-out sweep of the global coefficient-compression exponent λ (coef' = guess^λ) against the
 * ONE authority: held-out belief score on real history (BeliefHeldOutScorer). λ is not a
 * BeliefConfig field — it transforms fixed guessed constants into other fixed constants — so it is
 * swept via BacktestData.withCoefLambda, not the BeliefConfig coordinate descent. Human-gated:
 * the printed argmin + curve is adopted into ExerciseCoefficients.LAMBDA by hand (Task 5). Skips
 * without history.json.
 *
 * Design evidence (already gathered, this session): forward-chaining cold-start RMSE improved
 * 0.275→0.226 ln at λ≈0.75–0.80 under leave-one-exercise-out (scratchpad coldstart.py/compress.py).
 */
class CoefExponentFitTest {

    // Wide grid; an argmin on the EDGE means "widen the grid", not "adopt".
    private val grid = listOf(0.50f, 0.60f, 0.65f, 0.70f, 0.75f, 0.80f, 0.85f, 0.90f, 0.95f, 1.00f, 1.10f)

    @Test
    fun sweepCoefExponentHeldOut() {
        val data = BacktestData.loadOrNull()
        Assume.assumeTrue("backtest/history.json not present; skipping", data != null)
        data!!

        val config = BeliefConfig()
        val curve = grid.map { lambda ->
            val r = BeliefHeldOutScorer.score(data.withCoefLambda(lambda), config).report
            Triple(lambda, r.totalDistance, r.totalDistance / r.scoredSets)
        }
        val best = curve.minByOrNull { it.second }!!

        val sb = StringBuilder()
        sb.appendLine("=== Part B fit: coefficient compression λ, held-out belief score ===")
        sb.appendLine("  ${"lambda".padStart(6)}  ${"total".padStart(10)}  ${"per-set".padStart(9)}")
        for ((lambda, total, perSet) in curve) {
            val mark = when {
                lambda == best.first -> "  <-- best"
                lambda == 1.0f -> "  <-- current (identity)"
                else -> ""
            }
            sb.appendLine("  ${"%.2f".format(lambda).padStart(6)}  ${"%.4f".format(total).padStart(10)}  ${"%.5f".format(perSet).padStart(9)}$mark")
        }
        sb.appendLine("best λ = ${"%.2f".format(best.first)} (total ${"%.4f".format(best.second)} / per-set ${"%.5f".format(best.third)})")
        println(sb)
    }
}
