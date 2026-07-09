package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceVarTest {
    private val config = EstimatorConfig()
    private val updater = BeliefUpdater(config)
    private val DAY = 24L * 60 * 60 * 1000

    @Test
    fun seedInitializesEvidenceVarToSeedVariance() {
        val b = ExerciseBelief.seed(e1rm = 38f, at = 0L, config = config)
        assertEquals(config.sigmaSeed * config.sigmaSeed, b.evidenceVar, 1e-9f)
    }

    @Test
    fun overrideInitializesEvidenceVarToOverrideVariance() {
        val b = ExerciseBelief.override(e1rm = 38f, at = 0L, config = config)
        assertEquals(config.sigmaOverride * config.sigmaOverride, b.evidenceVar, 1e-9f)
    }

    @Test
    fun ageGrowsEvidenceVar() {
        // A belief whose evidenceVar is below the cap must grow with idle time, like sigma2.
        val b = ExerciseBelief(mu = 3.6f, sigma2 = 0.01f, updatedAt = 0L, evidenceVar = 0.01f)
        val aged = updater.age(b, now = 30 * DAY, muscleLastObs = null)
        assertTrue("evidenceVar must grow with idle time (${aged.evidenceVar})", aged.evidenceVar > b.evidenceVar)
        assertEquals("evidenceVar ages exactly like sigma2 (same q)", aged.sigma2, aged.evidenceVar, 1e-9f)
    }
}
