package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.PredictiveDensity
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class StudentTTest {
    @Test fun cdfKnownValues() {
        assertEquals(0.5, StudentT.cdf(0.0, 5.0), 1e-9)          // symmetry
        assertEquals(0.75, StudentT.cdf(1.0, 1.0), 1e-6)         // Cauchy: F(1)=0.75
        assertEquals(0.5 + 0.5, StudentT.cdf(50.0, 3.0), 1e-3)   // far right tail ≈ 1
    }

    @Test fun largeNuApproachesGaussianScore() {
        // A single Gaussian-point obs scored by Student-t at large ν ≈ Gaussian predictive log-density.
        val predVar = 0.05
        val z = 0.3
        val obs = (0.3 * sqrt(predVar)).toFloat() // obs at predMean + z·sd, predMean = 0
        val gaussian = PredictiveDensity.gaussianLogDensity(obs, 0f, predVar.toFloat()).toDouble()
        val t = StudentTScorer(nu = 1e6).sessionScore(
            listOf(
                ScoredSet(1L, 1L, null, 0L, 0, 1,
                    io.github.fowles.stochastic_strength.domain.progression.SetObservation(
                        null, null, obs, noiseSd = 0f),
                    predMeanLn = 0f, cleanVar = predVar.toFloat()),
            ),
        )
        assertEquals(gaussian, t, 1e-3)
    }
}
