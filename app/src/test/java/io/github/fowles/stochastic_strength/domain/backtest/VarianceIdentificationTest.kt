package io.github.fowles.stochastic_strength.domain.backtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class VarianceIdentificationTest {

    @Test fun varianceStudy_onRealHistory_printsAndWrites() {
        val data = BacktestHarness.load()
        assumeTrue("no personal history.json fixture; skipping", data != null)
        data!!

        val report = VarianceIdentificationStudy.run(data)
        val text = VarianceIdentificationStudy.format(report)
        println(text)

        val out = File("build/variance-identification-report.txt")
        out.parentFile?.mkdirs()
        out.writeText(text)

        // Structural invariants: four candidates, each with a non-empty sweep, references finite.
        assertEquals(
            listOf("day-effect", "obs-noise", "student-t", "transfer-tau"),
            report.candidates.map { it.name },
        )
        assertTrue(report.b0.isFinite() && report.b1.isFinite())
        report.candidates.forEach { assertTrue(it.points.isNotEmpty() && it.heldOut.isFinite()) }
        // B1 (procNoise x16) is the known release-valve reference: it should beat B0 held-out.
        assertTrue(report.b1 > report.b0)
    }
}
