package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig

/** Joint (obsNoiseScale, σ_day) grid search over held-out one-step-ahead CV on the real history. */
object VarianceBudgetJointFit {
    val OBS_SCALES = listOf(1.0, 1.5, 2.0, 2.5, 3.0)
    val DAY_SDS = listOf(0.0, 0.08, 0.12, 0.16, 0.20, 0.24)

    data class Cell(val obsScale: Double, val sigmaDay: Double, val heldOut: Double)
    data class Result(val grid: List<Cell>, val best: Cell, val interiorObs: Boolean, val interiorDay: Boolean)

    fun run(user: RecalibrationHarness.UserHistory, minFold: Int = 8): Result {
        // heldOutTailScore telescopes the forward-chaining held-out sum under a FIXED config to two
        // replays (see RecalibrationHarness); the day-effect + obs-noise both flow through the config.
        val grid = OBS_SCALES.flatMap { os ->
            DAY_SDS.map { sd ->
                val cfg = EstimatorConfig(obsNoiseScale = os.toFloat(), sessionDayEffectSd = sd.toFloat())
                Cell(os, sd, RecalibrationHarness.heldOutTailScore(user, cfg, minFold))
            }
        }
        val best = grid.maxBy { it.heldOut }
        val interiorObs = best.obsScale != OBS_SCALES.first() && best.obsScale != OBS_SCALES.last()
        val interiorDay = best.sigmaDay != DAY_SDS.first() && best.sigmaDay != DAY_SDS.last()
        return Result(grid, best, interiorObs, interiorDay)
    }

    fun format(r: Result): String = buildString {
        appendLine("Variance-budget joint fit (obsNoiseScale × sessionDayEffectSd, held-out one-step CV)")
        appendLine("obsScale \\ σ_day: ${DAY_SDS.joinToString(" ") { "%.2f".format(it) }}")
        for (os in OBS_SCALES) {
            val row = DAY_SDS.map { sd -> r.grid.first { it.obsScale == os && it.sigmaDay == sd }.heldOut }
            appendLine("%.1f: %s".format(os, row.joinToString(" ") { "%.1f".format(it) }))
        }
        appendLine("BEST obsScale=%.2f σ_day=%.2f heldOut=%.2f interiorObs=%b interiorDay=%b"
            .format(r.best.obsScale, r.best.sigmaDay, r.best.heldOut, r.interiorObs, r.interiorDay))
        val verdict = if (r.interiorObs && r.interiorDay) "ADOPT (interior in both dims)"
            else "DO NOT ADOPT — pins a bound (release valve; widen grid or reconsider)"
        appendLine("VERDICT: $verdict")
    }
}
