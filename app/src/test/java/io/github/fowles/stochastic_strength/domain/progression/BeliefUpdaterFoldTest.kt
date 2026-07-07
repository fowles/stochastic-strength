package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sqrt

class BeliefUpdaterFoldTest {
    private val config = EstimatorConfig()
    private val updater = BeliefUpdater(config)

    /** Exact posterior moments by trapezoidal integration over x. */
    private fun numericPosterior(mu: Double, sigma2: Double, lower: Double?, upper: Double?, s: Double): Pair<Double, Double> {
        val sigma = sqrt(sigma2)
        val n = 8001
        val lo = mu - 8 * sigma
        val hi = mu + 8 * sigma
        val dx = (hi - lo) / (n - 1)
        var m0 = 0.0; var m1 = 0.0; var m2 = 0.0
        for (i in 0 until n) {
            val x = lo + i * dx
            val prior = exp(-0.5 * (x - mu) * (x - mu) / sigma2)
            val pu = if (upper != null) NormalCdf.cdf(((upper - x) / s).toFloat()).toDouble() else 1.0
            val pl = if (lower != null) NormalCdf.cdf(((lower - x) / s).toFloat()).toDouble() else 0.0
            val wgt = prior * (pu - pl)
            m0 += wgt; m1 += wgt * x; m2 += wgt * x * x
        }
        val mean = m1 / m0
        return mean to (m2 / m0 - mean * mean)
    }

    private fun belief(mu: Float, sigma: Float) = ExerciseBelief(mu, sigma * sigma, updatedAt = 0L)

    @Test
    fun twoSidedCensoredFoldMatchesNumericalIntegration() {
        val prior = belief(4.0f, 0.15f)
        val (l, u) = 3.95f to 4.05f
        val s = 0.04f
        val folded = updater.foldCensored(prior, l, u, s, at = 0L, muscleLastObs = null)
        val (em, ev) = numericPosterior(4.0, 0.15 * 0.15, 3.95, 4.05, 0.04)
        assertEquals(em.toFloat(), folded.mu, 2e-3f)
        assertEquals(ev.toFloat(), folded.sigma2, ev.toFloat() * 0.05f)
    }

    @Test
    fun oneSidedLowerFoldMatchesNumericalIntegration() {
        val prior = belief(4.0f, 0.20f)
        val folded = updater.foldCensored(prior, 4.10f, null, 0.05f, 0L, null)
        val (em, ev) = numericPosterior(4.0, 0.04, 4.10, null, 0.05)
        assertEquals(em.toFloat(), folded.mu, 2e-3f)
        assertEquals(ev.toFloat(), folded.sigma2, ev.toFloat() * 0.05f)
        assertTrue("lower-bound obs must raise the mean", folded.mu > prior.mu)
    }

    @Test
    fun oneSidedUpperFoldMatchesNumericalIntegration() {
        val prior = belief(4.0f, 0.20f)
        val folded = updater.foldCensored(prior, null, 3.90f, 0.05f, 0L, null)
        val (em, ev) = numericPosterior(4.0, 0.04, null, 3.90, 0.05)
        assertEquals(em.toFloat(), folded.mu, 2e-3f)
        assertEquals(ev.toFloat(), folded.sigma2, ev.toFloat() * 0.05f)
        assertTrue("upper-bound obs must lower the mean", folded.mu < prior.mu)
    }

    @Test
    fun gaussianFoldIsStandardKalman() {
        val prior = belief(4.0f, 0.10f)
        val folded = updater.foldGaussian(prior, obsLnE1rm = 3.8f, noiseSd = 0.05f, at = 0L, muscleLastObs = null)
        val k = 0.01f / (0.01f + 0.0025f)
        assertEquals(4.0f + k * (3.8f - 4.0f), folded.mu, 1e-4f)
        assertEquals((1 - k) * 0.01f, folded.sigma2, 1e-5f)
    }

    @Test
    fun degenerateWindowFallsBackToGaussianAtViolatedBound() {
        // Prior far above the interval: Z ≈ 0 ⇒ Gaussian at the upper bound.
        val prior = belief(5.0f, 0.05f)
        val folded = updater.foldCensored(prior, 3.0f, 3.1f, 0.02f, 0L, null)
        val gauss = updater.foldGaussian(prior, 3.1f, 0.02f, 0L, null)
        assertEquals(gauss.mu, folded.mu, 1e-5f)
        assertEquals(gauss.sigma2, folded.sigma2, 1e-6f)
    }

    @Test
    fun sigmaIsClampedToConfiguredBounds() {
        val tight = updater.foldGaussian(belief(4f, config.sigmaMin), 4f, 1e-4f, 0L, null)
        assertTrue(tight.sigma2 >= config.sigmaMin * config.sigmaMin * 0.999f)
        val seeded = ExerciseBelief.seed(60f, at = 5L, config = config)
        assertEquals(config.sigmaSeed * config.sigmaSeed, seeded.sigma2, 1e-6f)
        assertEquals(5L, seeded.updatedAt)
        val over = ExerciseBelief.override(60f, at = 7L, config = config)
        assertEquals(config.sigmaOverride * config.sigmaOverride, over.sigma2, 1e-6f)
    }
}
