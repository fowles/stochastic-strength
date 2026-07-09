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

    @Test
    fun gaussianFoldReducesEvidenceVar() {
        val seed = ExerciseBelief.seed(e1rm = 38f, at = 0L, config = config)
        val after = updater.foldGaussian(seed, obsLnE1rm = 3.4f, noiseSd = 0.08f, at = 0L, muscleLastObs = null)
        assertTrue("a fold must add evidence (lower evidenceVar): ${after.evidenceVar}",
            after.evidenceVar < seed.evidenceVar)
    }

    @Test
    fun censoredFoldReducesEvidenceVar() {
        val seed = ExerciseBelief.seed(e1rm = 38f, at = 0L, config = config)
        val after = updater.foldCensored(seed, lowerLn = 3.3f, upperLn = 3.5f, noiseSd = 0.08f, at = 0L, muscleLastObs = null)
        assertTrue("a censored fold must add evidence: ${after.evidenceVar}",
            after.evidenceVar < seed.evidenceVar)
    }

    @Test
    fun adaptationDoesNotContaminateEvidenceVar() {
        // Fold a consistent down-run so adaptation fires and inflates sigma2. evidenceVar must be
        // essentially identical to the run WITHOUT adaptation (threshold huge) — proving it tracks
        // accumulated evidence, not the adaptation-inflated variance.
        val noAdapt = EstimatorConfig(adaptRunThreshold = 1e6f)
        val u2 = BeliefUpdater(noAdapt)
        var withAdapt = ExerciseBelief(mu = 3.6f, sigma2 = 0.02f * 0.02f, updatedAt = 0L, evidenceVar = 0.02f * 0.02f)
        var without = withAdapt
        repeat(5) {
            withAdapt = updater.foldGaussian(withAdapt, obsLnE1rm = 3.2f, noiseSd = 0.05f, at = 0L, muscleLastObs = null)
            without = u2.foldGaussian(without, obsLnE1rm = 3.2f, noiseSd = 0.05f, at = 0L, muscleLastObs = null)
        }
        assertTrue("adaptation must inflate sigma2", withAdapt.sigma2 > without.sigma2)
        assertEquals("but evidenceVar must be untouched by adaptation",
            without.evidenceVar, withAdapt.evidenceVar, 1e-6f)
    }
}
