package io.github.fowles.stochastic_strength.domain.backtest

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One-shot baseline freezer for [BacktestComparisonTest]. Freezes the CURRENT code's
 * [BacktestHarness.replayPolicyPrescriptions] (the production policy path) as the reference.
 * Skipped unless history.json is present locally AND no baseline exists yet; deleting
 * baseline_prescriptions.json re-arms it. Re-baseline only deliberately, after attributing the
 * intended deltas vs the existing reference (as done at the 2026-07-09 phase-2 re-baseline) —
 * casually regenerating would hide regressions. The reference is machine-local and gitignored.
 */
class BacktestBaselineGeneratorTest {
    @Test
    fun freezeBaselineFromCurrentMain() {
        val data = BacktestHarness.load()
        assumeTrue("no local backtest history; skipping", data != null)
        assumeTrue("baseline already frozen; delete manually to regenerate", !BacktestHarness.baselineFile().exists())
        val rows = BacktestHarness.replayPolicyPrescriptions(data!!)
        assertTrue("history produced no prescriptions", rows.isNotEmpty())
        BacktestHarness.writeBaseline(rows)
        println("Frozen ${rows.size} baseline prescriptions to ${BacktestHarness.baselineFile()}")
    }
}
