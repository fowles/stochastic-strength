package io.github.fowles.stochastic_strength.domain.backtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RecalibrationReportTest {

    @Test
    fun recalibrationReport_onRealHistory_printsAndWrites() {
        val data = BacktestHarness.load()
        assumeTrue("no personal history.json fixture; skipping", data != null)
        data!!

        val user = RecalibrationHarness.UserHistory(data.history) { data.newSnapshot() }
        val report = RecalibrationHarness.runHarness(listOf(user))
        val text = RecalibrationHarness.format(report)
        println(text)

        val out = File("build/recalibration-report.txt")
        out.parentFile?.mkdirs()
        out.writeText(text)

        // Meaningful structural invariants (report is evidence, but the run must be well-formed).
        val n = data.history.sessions.count { it.endTime != null }
        assertEquals(listOf("drift", "fatigue", "procNoise", "tau"), report.params.map { it.name })
        assertEquals(maxOf(0, n - 8), report.foldCount)   // folds k = 8 .. N-1
        report.params.forEach { assertEquals(report.foldCount, it.trajectory.size) }
        // Every proposed multiplier lands inside the widened harness box.
        report.params.forEach {
            assertTrue(it.proposedMultiplier in (1.0 / 16.0)..16.0)
        }
    }
}
