package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class PredictiveDensityTest {
    // Reference Gaussian log-density: -0.5*ln(2πV) - (x-m)²/(2V).
    @Test fun gaussianMatchesClosedForm() {
        val m = ln(100f); val v = 0.05f; val x = ln(105f)
        val expected = (-0.5 * ln(2 * PI * v) - (x - m).toDouble() * (x - m) / (2 * v))
        assertEquals(expected, PredictiveDensity.gaussianLogDensity(x, m, v).toDouble(), 1e-4)
    }

    // Interval mass by coarse numerical integration of the predictive Normal.
    @Test fun censoredMatchesNumericalIntegration() {
        val m = ln(100f); val v = 0.04f; val lo = ln(98f); val hi = ln(104f)
        val sd = sqrt(v)
        var mass = 0.0
        val steps = 20000; val a = m - 6 * sd; val b = m + 6 * sd; val dx = (b - a) / steps
        var x = a
        while (x < b) {
            if (x in lo..hi) mass += exp(-0.5 * ((x - m) / sd).toDouble() * ((x - m) / sd)) / (sd * sqrt(2 * PI)) * dx
            x += dx
        }
        // Rectangle-rule Riemann sum has ~O(dx) boundary error at the interval edges; 5e-3 keeps
        // this a real closed-form check (~0.5%) while the 1e-4 consistency test below is the tight guard.
        assertEquals(ln(mass), PredictiveDensity.censoredLogMass(lo, hi, m, v).toDouble(), 5e-3)
    }

    @Test fun oneSidedLowerIsHalfAtMean() {
        // Mass of [mean, +∞) under the predictive Normal is 0.5 → log ≈ ln(0.5).
        val m = ln(50f); val v = 0.06f
        assertEquals(ln(0.5), PredictiveDensity.censoredLogMass(m, null, m, v).toDouble(), 1e-3)
    }

    // Consistency guard: the scorer's interval mass equals the fold's own Z (never diverge).
    @Test fun intervalLogMassAgreesWithFoldZ() {
        val m = ln(80f); val v = 0.05f; val lo = ln(78f); val hi = ln(85f); val sd = sqrt(v)
        val a = ((lo - m) / sd).coerceIn(-6f, 6f); val b = ((hi - m) / sd).coerceIn(-6f, 6f)
        val z = NormalCdf.cdf(b) - NormalCdf.cdf(a)
        assertEquals(ln(z.toDouble()), NormalCdf.intervalLogMass(m, sd, lo, hi).toDouble(), 1e-4)
    }
}
