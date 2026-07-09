package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class ProjectorEvidenceGateTest {
    private val config = EstimatorConfig()
    private val projector = MuscleStrengthProjector(config)

    @Test
    fun neffReadsEvidenceVarNotSigma() {
        // Inflated sigma2 (adaptation) but tight evidenceVar (well observed) => HIGH n_eff.
        val inflated = ExerciseBelief(mu = 3.0f, sigma2 = 0.09f * 0.09f, updatedAt = 0L, evidenceVar = 0.03f * 0.03f)
        val seedFloorVar = config.sigmaSeed * config.sigmaSeed
        val expected = ((1f / inflated.evidenceVar - 1f / seedFloorVar) * config.poolObsVar).coerceAtLeast(0f)
        assertEquals(expected, projector.neff(inflated), 1e-6f)
        assertTrue("n_eff must be well above the inflated-sigma value", projector.neff(inflated) > 1.0f)
    }

    @Test
    fun wellEvidencedBeliefResistsConfidentSiblings() {
        // Self (id 55, coef 0.30): mean ~19 kg fresh, sigma2 INFLATED by a surprise, but well-evidenced
        // (evidenceVar 0.03²). Sibling (id 48, coef 1.00): strong ~120 kg, tight, and MORE-evidenced than
        // self (evidenceVar 0.02², like the real BSS case where squat has more folds) — so siblingExcess
        // is strictly positive and the capped bridge pull IS active. The evidence gate must still keep
        // self near its own 19 kg via a strong absolute self-anchor, NOT let the sibling pull it toward
        // the muscle level's prediction for self (~30+ kg).
        val beliefs = mapOf(
            55L to ExerciseBelief(mu = ln(19f), sigma2 = 0.09f * 0.09f, updatedAt = 0L, evidenceVar = 0.03f * 0.03f),
            48L to ExerciseBelief(mu = ln(120f), sigma2 = 0.02f * 0.02f, updatedAt = 0L, evidenceVar = 0.02f * 0.02f),
        )
        val seedCoef = mapOf(55L to 0.30f, 48L to 1.00f)
        val proj = projector.project(beliefs, seedCoef, listOf(55L, 48L), now = 0L)
        val self = proj.effectiveE1rm.getValue(55L)
        assertTrue("self must stay near its own 19 kg belief, not be pulled toward ~36 kg (got $self)",
            self < 20f)
    }
}
