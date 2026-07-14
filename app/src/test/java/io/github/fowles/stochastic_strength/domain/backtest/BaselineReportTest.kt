package io.github.fowles.stochastic_strength.domain.backtest

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Phase-0 exit artifact (spec): the held-out baseline of main's UNMODIFIED estimator over the real
 * history — the number the rebuilt stack must beat. Skips when the local history export is absent.
 * Writes phase0_baseline.json (gitignored) and prints the report; the headline numbers are recorded
 * in docs/superpowers/plans/2026-07-14-phase0-backtest-harness.md.
 */
class BaselineReportTest {

    @Test
    fun recordMainStackBaseline() {
        val data = BacktestData.loadOrNull()
        Assume.assumeTrue("backtest/history.json not present; skipping baseline report", data != null)
        data!!

        val report = HeldOutScorer.score(data)
        val violations = CapViolationDiagnostic.violations(data)

        assertTrue("baseline must score real sets", report.scoredSets > 0)

        val json = JSONObject()
            .put("generatedAt", System.currentTimeMillis())
            .put("stack", "main")
            .put("totalDistance", report.totalDistance)
            .put("scoredSets", report.scoredSets)
            .put("skippedSets", report.skippedSets)
            .put("meanDistancePerSet", report.totalDistance / report.scoredSets)
            .put("capViolations", violations.size)
            .put("perSession", JSONArray().apply {
                report.perSession.forEach {
                    put(JSONObject().put("s", it.sessionId).put("d", it.distance).put("n", it.scoredSets))
                }
            })
        BacktestData.baselineFile().writeText(json.toString(2))

        val sb = StringBuilder()
        sb.appendLine("=== Phase 0 baseline: main's estimator on real history ===")
        sb.appendLine("sessions scored : ${report.perSession.size}")
        sb.appendLine("sets scored     : ${report.scoredSets} (skipped: ${report.skippedSets})")
        sb.appendLine("total distance  : ${"%.4f".format(report.totalDistance)} ln-units")
        sb.appendLine("mean per set    : ${"%.5f".format(report.totalDistance / report.scoredSets)} ln-units")
        sb.appendLine("cap violations  : ${violations.size}")
        violations.forEach {
            sb.appendLine("  session ${it.sessionId} exercise ${it.exerciseId}: predicted %.1f kg > cap %.1f kg".format(it.predictedE1rm, it.capE1rm))
        }
        println(sb)
    }
}
