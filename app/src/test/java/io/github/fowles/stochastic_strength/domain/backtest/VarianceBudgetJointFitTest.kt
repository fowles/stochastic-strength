package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class VarianceBudgetJointFitTest {
    @Test fun jointFitReport() {
        val data = BacktestHarness.load()
        assumeTrue("no personal history.json fixture; skipping", data != null)
        data!!
        val user = RecalibrationHarness.UserHistory(data.history) { data.newSnapshot() }
        val result = VarianceBudgetJointFit.run(user)

        // Light-lift swing (spec §6): compare the adopted argmax config vs today's default.
        val bestCfg = EstimatorConfig(
            obsNoiseScale = result.best.obsScale.toFloat(),
            sessionDayEffectSd = result.best.sigmaDay.toFloat(),
        )
        val swingBest = lightestLiftSwing(BacktestHarness.replayPolicyPrescriptions(data, bestCfg))
        val swingDefault = lightestLiftSwing(BacktestHarness.replayPolicyPrescriptions(data, EstimatorConfig()))

        val report = VarianceBudgetJointFit.format(result) +
            "\nlight-lift swing  best=$swingBest  default=$swingDefault\n"
        val out = File("build/variance-budget-jointfit-report.txt")
        out.parentFile?.mkdirs()
        out.writeText(report)
        println(report)
    }
}
