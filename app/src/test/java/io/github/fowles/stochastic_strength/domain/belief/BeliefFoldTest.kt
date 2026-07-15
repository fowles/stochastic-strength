package io.github.fowles.stochastic_strength.domain.belief

import org.junit.Assert.assertEquals
import org.junit.Test

class BeliefFoldTest {
    private val DAY = 24L * 60 * 60 * 1000

    // Explicit config in every test — defaults are re-fit later and must not be load-bearing here.
    private val config = BeliefConfig(
        sigmaSeed = 0.15f, sigmaOverride = 0.10f,
        phi = 0.05f, qPerDay = 1e-3f,
        sigmaObsRir = 0.10f, sigmaObsFail = 0.10f,
        tau = 0.10f, sigma2Floor = 4e-4f, sigma2Cap = 0.25f,
    )
    private val fold = BeliefFold(config)

    @Test
    fun agingGrowsVarianceLinearlyPerIdleDay() {
        val b = Belief(mu = 4.6f, sigma2 = 0.01f, updatedAt = 0L)
        val aged = fold.aged(b, now = 10 * DAY)
        assertEquals(4.6f, aged.mu, 1e-6f)                     // mu never drifts
        assertEquals(0.01f + 10 * 1e-3f, aged.sigma2, 1e-6f)   // q per idle day
        assertEquals(10 * DAY, aged.updatedAt)
    }

    @Test
    fun agingIsClampedToTheVarianceCapAndNeverNegativeTime() {
        val b = Belief(mu = 4.6f, sigma2 = 0.24f, updatedAt = 10 * DAY)
        assertEquals(0.25f, fold.aged(b, now = 100 * DAY).sigma2, 1e-6f)  // cap (flat guard)
        assertEquals(0.24f, fold.aged(b, now = 0L).sigma2, 1e-6f)         // clock skew: age >= 0
    }
}
