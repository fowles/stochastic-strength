package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig

/**
 * Coordinate-descent fitting of the belief stack's `fitted` constants against the ONE authority
 * (held-out score on real history — constitution rule 1). Sensitivity curves are 1-D sweeps at the
 * final optimum, recorded in the plan when a constant is admitted (rule 2). The fitness function
 * never sees policy clamps (rule 3).
 */
object BeliefFitHarness {

    data class Axis(
        val name: String,
        val values: List<Float>,
        val get: (BeliefConfig) -> Float,
        val with: (BeliefConfig, Float) -> BeliefConfig,
    )

    /** Wide log-spaced grids; an optimum on a grid EDGE means "widen the grid", not "adopt". */
    val AXES = listOf(
        Axis("fatiguePerSetEstimate", listOf(0f, 0.01f, 0.02f, 0.03f, 0.05f, 0.08f), { it.fatiguePerSetEstimate }, { c, v -> c.copy(fatiguePerSetEstimate = v) }),
        Axis("confidenceDecayEstimate", listOf(1e-6f, 3e-6f, 1e-5f, 3e-5f, 1e-4f, 3e-4f, 1e-3f, 3e-3f), { it.confidenceDecayEstimate }, { c, v -> c.copy(confidenceDecayEstimate = v) }),
        Axis("perSetDoubtEstimate", listOf(0.001f, 0.002f, 0.005f, 0.01f, 0.02f, 0.04f, 0.07f, 0.10f, 0.15f, 0.25f), { it.perSetDoubtEstimate }, { c, v -> c.copy(perSetDoubtEstimate = v) }),
        Axis("crossLiftIndependenceEstimate", listOf(0.05f, 0.08f, 0.12f, 0.20f, 0.30f, 0.50f, 0.80f, 1.20f), { it.crossLiftIndependenceEstimate }, { c, v -> c.copy(crossLiftIndependenceEstimate = v) }),
    )

    data class FitResult(
        val best: BeliefConfig,
        val bestScore: Double,
        val curves: Map<String, List<Pair<Float, Double>>>,
    )

    fun fit(
        start: BeliefConfig,
        axes: List<Axis> = AXES,
        passes: Int = 3,
        score: (BeliefConfig) -> Double,
    ): FitResult {
        var best = start
        var bestScore = score(best)
        repeat(passes) {
            for (axis in axes) {
                for (v in axis.values) {
                    if (v == axis.get(best)) continue
                    val s = score(axis.with(best, v))
                    if (s < bestScore - 1e-12) { best = axis.with(best, v); bestScore = s }
                }
            }
        }
        val curves = axes.associate { axis ->
            axis.name to axis.values.map { v ->
                v to if (v == axis.get(best)) bestScore else score(axis.with(best, v))
            }
        }
        return FitResult(best, bestScore, curves)
    }
}
