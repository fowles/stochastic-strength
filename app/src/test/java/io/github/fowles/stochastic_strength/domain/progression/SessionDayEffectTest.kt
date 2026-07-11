package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDayEffectTest {
    @Test fun zeroSigmaDayGivesZeroPosterior() {
        val post = SessionDayEffect.estimate(
            sigmaDay = 0f,
            observations = listOf(SessionDayEffect.Residual(0.5f, 0.01f), SessionDayEffect.Residual(0.3f, 0.01f)),
        )
        assertEquals(0f, post.mean, 0f)
        assertEquals(0f, post.variance, 0f)
    }

    @Test fun noObservationsReturnsPrior() {
        val post = SessionDayEffect.estimate(sigmaDay = 0.2f, observations = emptyList())
        assertEquals(0f, post.mean, 0f)
        assertEquals(0.04f, post.variance, 1e-6f)
    }

    @Test fun twoEqualResidualsPullMeanTowardTheirValueAndShrinkVariance() {
        // Prior N(0, 0.2²=0.04). Two obs at +0.1 with obsVar 0.01 each.
        // Posterior precision = 1/0.04 + 2/0.01 = 25 + 200 = 225 -> var = 1/225 ≈ 0.004444.
        // Posterior mean = var * (0 + 0.1/0.01 + 0.1/0.01) = 0.004444 * 20 ≈ 0.08889.
        val post = SessionDayEffect.estimate(
            sigmaDay = 0.2f,
            observations = listOf(SessionDayEffect.Residual(0.1f, 0.01f), SessionDayEffect.Residual(0.1f, 0.01f)),
        )
        assertEquals(0.004444f, post.variance, 1e-5f)
        assertEquals(0.08889f, post.mean, 1e-4f)
    }
}
