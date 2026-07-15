package io.github.fowles.stochastic_strength.domain.belief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class BeliefPrescriberTest {
    @Test
    fun targetIsThe30thPercentileOfBelievedCapacity() {
        val eff = EffectiveBelief(mu = ln(100f), sigma2 = 0.04f)
        assertEquals(exp(ln(100f) - BeliefPrescriber.Z * sqrt(0.04f)), BeliefPrescriber.targetE1rm(eff), 1e-4f)
    }

    @Test
    fun coldStartsAreAutomaticallyHumbleAndCertaintyRaisesTheTarget() {
        val cold = BeliefPrescriber.targetE1rm(EffectiveBelief(ln(100f), 0.25f))
        val warm = BeliefPrescriber.targetE1rm(EffectiveBelief(ln(100f), 0.0025f))
        assertTrue(cold < warm)
        assertTrue(warm < 100f)   // never above mu
    }
}
