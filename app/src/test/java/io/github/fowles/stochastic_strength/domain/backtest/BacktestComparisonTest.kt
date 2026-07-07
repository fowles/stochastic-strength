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

    // PINNED 2026-07-06 against the baseline frozen from pre-change commit 92205abd (22 sessions,
    // 1452 rows). Observed worst delta: 14% — Preacher Curl capped 35→30 lb by the clear failure
    // ceiling from its session-13 misses (4/8 at 20.4 kg, uncounted at 15.88 kg; the old system
    // re-prescribed the failed 35 lb). Also 3%: Sumo Deadlift capped 170→165 lb after session 27's
    // drop-cascade (7/10 at both 83.9 and 77.1 kg). No HURT sets in history, so no healing deltas.
    // Every >2% delta attributable to intended failure-ceiling semantics. Band = worst + 0.05.
    private val BAND = 0.19f

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
