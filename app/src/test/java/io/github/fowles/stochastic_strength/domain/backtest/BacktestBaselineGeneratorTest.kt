package io.github.fowles.stochastic_strength.domain.backtest

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One-shot baseline freezer. Skipped unless history.json is present locally AND no baseline
 * exists yet. Deleting baseline_prescriptions.json re-arms it — only do that BEFORE phase-1
 * behavior changes land, or from a jj commit at the pre-phase-1 baseline.
 */
class BacktestBaselineGeneratorTest {
    @Test
    fun freezeBaselineFromCurrentMain() {
        val data = BacktestHarness.load()
        assumeTrue("no local backtest history; skipping", data != null)
        assumeTrue("baseline already frozen; delete manually to regenerate", !BacktestHarness.baselineFile().exists())
        val rows = BacktestHarness.replayProjectorPrescriptions(data!!)
        assertTrue("history produced no prescriptions", rows.isNotEmpty())
        BacktestHarness.writeBaseline(rows)
        println("Frozen ${rows.size} baseline prescriptions to ${BacktestHarness.baselineFile()}")
    }
}
