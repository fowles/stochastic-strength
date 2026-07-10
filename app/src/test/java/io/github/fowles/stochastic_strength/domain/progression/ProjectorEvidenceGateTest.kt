package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class ProjectorEvidenceGateTest {
    private val config = EstimatorConfig()
    private val projector = MuscleStrengthProjector(config)

    @Test
    fun poolPrecisionReadsEvidenceVarNotSigma() {
        // Inflated sigma2 (adaptation) but tight evidenceVar (well observed) => HIGH poolPrecision.
        // If poolPrecision incorrectly read sigma2 instead of evidenceVar, the sigma2 and evidenceVar
        // terms cancel here. We test via a case where the two tracks diverge significantly: make
        // tau very small so that the variance term dominates and the ratio between the two is large.
        val inflated = ExerciseBelief(mu = 3.0f, sigma2 = 0.09f * 0.09f, updatedAt = 0L, evidenceVar = 0.03f * 0.03f)
        val tauTight = 0.01f   // tiny τ so evidenceVar vs sigma2 matters: 1/(0.0009+.0001) vs 1/(0.0081+.0001)
        val expected = 1f / (inflated.evidenceVar + tauTight * tauTight)
        assertEquals(expected, projector.poolPrecision(inflated, tauTight), 1e-3f)
        // evidenceVar-based: ~1/0.001 = 1000; sigma2-based: ~1/0.0082 ≈ 122; ratio > 7x
        val inflatedSigmaPrec = 1f / (inflated.sigma2 + tauTight * tauTight)
        assertTrue("precision from evidenceVar must be well above precision from inflated sigma2",
            projector.poolPrecision(inflated, tauTight) > inflatedSigmaPrec * 5f)
    }

    @Test
    fun wellEvidencedBeliefResistsConfidentSiblings() {
        // Self (id 55, coef 0.30): mean ~19 kg fresh, sigma2 INFLATED by a surprise, but well-evidenced
        // (evidenceVar 0.03²). Sibling (id 48, coef 1.00): strong ~120 kg, tight, and MORE-evidenced than
        // self (evidenceVar 0.02², like the real BSS case where squat has more folds) — so sibling
        // has higher poolPrecision. The evidence gate must still keep self near its own 19 kg via a
        // strong absolute self-anchor (ownPrec = 1/evidenceVar), NOT let the sibling pull it toward
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
