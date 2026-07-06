package io.github.fowles.stochastic_strength.domain.backtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Old-vs-new gate over the real exported history (spec §9). Skipped when the local fixture
 * files are absent. BAND is pinned after inspecting the printed delta table — phase-1 deltas
 * must be attributable to the intended HURT-healing / failure-ceiling semantic changes only.
 */
class BacktestComparisonTest {

    // PROVISIONAL — unpinned until the real-history fixture (app/src/test/resources/backtest/history.json)
    // exists. Once the fixture is in place: run the test, inspect the printed delta table, confirm
    // every >2% delta is attributable to a HURT event or a failure ceiling in the surrounding
    // sessions, then tighten to (observed worst delta + 0.05) and record the value here.
    private val BAND = 0.25f

    @Test
    fun policyPrescriptionsStayWithinBandOfFrozenBaselineAndNeverGoNaN() {
        val data = BacktestHarness.load()
        assumeTrue("no local backtest history; skipping", data != null)
        val baseline = BacktestHarness.readBaseline()
        assumeTrue("baseline not frozen; run BacktestBaselineGeneratorTest first", baseline != null)

        val current = BacktestHarness.replayPolicyPrescriptions(data!!)
        assertTrue("no prescriptions produced", current.isNotEmpty())
        current.forEach { assertFalse("NaN weight at $it", it.weightKg.isNaN()) }

        val baseByKey = baseline!!.associateBy { it.sessionId to it.exerciseId }
        var worst = 0f
        var worstDesc = ""
        val report = StringBuilder("session exercise old new delta\n")
        for (row in current) {
            val old = baseByKey[row.sessionId to row.exerciseId] ?: continue
            if (old.weightKg <= 0f) continue
            val rel = abs(row.weightKg - old.weightKg) / old.weightKg
            if (rel > 0.02f) {
                report.appendLine("${row.sessionId} ${row.exerciseId} ${old.weightKg} ${row.weightKg} ${(rel * 100).roundToInt()}%")
            }
            if (rel > worst) {
                worst = rel
                worstDesc = "session=${row.sessionId} exercise=${row.exerciseId} old=${old.weightKg} new=${row.weightKg}"
            }
        }
        println(report)
        println("worst relative delta: ${(worst * 100).roundToInt()}% ($worstDesc)")
        assertTrue("worst delta $worst ($worstDesc) exceeds band $BAND", worst <= BAND)
    }
}
