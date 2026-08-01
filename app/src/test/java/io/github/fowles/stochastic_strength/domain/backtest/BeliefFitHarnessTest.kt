package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class BeliefFitHarnessTest {
    @Test
    fun coordinateDescentFindsTheGridMinimumOfASeparableBowl() {
        val axes = listOf(
            BeliefFitHarness.Axis("fatiguePerSetEstimate", listOf(0f, 0.01f, 0.02f, 0.05f), { it.fatiguePerSetEstimate }, { c, v -> c.copy(fatiguePerSetEstimate = v) }),
            BeliefFitHarness.Axis("tau", listOf(0.05f, 0.10f, 0.20f), { it.tau }, { c, v -> c.copy(tau = v) }),
        )
        // Separable bowl with minimum at fatiguePerSetEstimate=0.02, tau=0.10.
        val score = { c: BeliefConfig -> (abs(c.fatiguePerSetEstimate - 0.02f) + abs(c.tau - 0.10f)).toDouble() }
        val result = BeliefFitHarness.fit(BeliefConfig(fatiguePerSetEstimate = 0f, tau = 0.20f), axes, passes = 2, score = score)
        assertEquals(0.02f, result.best.fatiguePerSetEstimate, 1e-7f)
        assertEquals(0.10f, result.best.tau, 1e-7f)
        assertEquals(0.0, result.bestScore, 1e-9)
        // Sensitivity curves cover every grid value of every axis, scored at the optimum of the others.
        assertEquals(4, result.curves["fatiguePerSetEstimate"]!!.size)
        assertEquals(0.02, result.curves["fatiguePerSetEstimate"]!![0].second, 1e-6)   // |0−0.02| at tau*=0.10
    }
}
