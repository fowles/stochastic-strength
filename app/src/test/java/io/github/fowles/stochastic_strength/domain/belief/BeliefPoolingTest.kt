package io.github.fowles.stochastic_strength.domain.belief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.ln

class BeliefPoolingTest {
    private val config = BeliefConfig(
        sigmaSeed = 0.15f, sigmaOverride = 0.10f,
        phi = 0.05f, qPerDay = 0f,           // qPerDay=0: aging is a no-op so numbers are exact
        sigmaObs = 0.10f,
        tau = 0.10f, sigma2Floor = 4e-4f, sigma2Cap = 0.25f,
    )
    private val pooling = BeliefPooling(config)

    // Muscle: A (coef 1.0, tight belief), B (coef 0.8, looser), C (coef 0.5, no belief).
    private val beliefs = mapOf(
        1L to Belief(mu = ln(100f), sigma2 = 0.01f, updatedAt = 0L),
        2L to Belief(mu = ln(72f), sigma2 = 0.04f, updatedAt = 0L),
    )
    private val coef = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 0.5f)
    private val ids = listOf(1L, 2L, 3L)

    // Transparent restatement of the spec math (weights, not the SUT's code).
    private fun w(sigma2: Float) = 1f / (sigma2 + config.tau * config.tau)

    @Test
    fun ownBeliefBlendsWithLeaveOneOutSiblingPrediction() {
        val result = pooling.effective(beliefs, coef, ids, now = 0L)
        // LOO level for A = B's vote alone; prediction variance = level var + tau².
        val vB = ln(72f) - ln(0.8f)
        val predVar = 1f / w(0.04f) + config.tau * config.tau
        val pOwn = 1f / 0.01f
        val pSib = 1f / predVar
        val expectedMu = (pOwn * ln(100f) + pSib * (ln(1.0f) + vB)) / (pOwn + pSib)
        val a = result.effective[1L]!!
        assertEquals(expectedMu, a.mu, 1e-5f)
        assertEquals(1f / (pOwn + pSib), a.sigma2, 1e-6f)
    }

    @Test
    fun beliefLessExerciseIsPredictedFromTheFullPool() {
        val result = pooling.effective(beliefs, coef, ids, now = 0L)
        val wA = w(0.01f); val wB = w(0.04f)
        val level = (wA * (ln(100f) - ln(1.0f)) + wB * (ln(72f) - ln(0.8f))) / (wA + wB)
        val c = result.effective[3L]!!
        assertEquals(ln(0.5f) + level, c.mu, 1e-5f)
        assertEquals(1f / (wA + wB) + config.tau * config.tau, c.sigma2, 1e-6f)
        assertEquals(level, result.levelLn!!, 1e-5f)
    }

    @Test
    fun lonelyVoterFallsBackToItsOwnBelief() {
        // Only A has a belief: its LOO pool is empty → effective = own belief, unshrunk.
        val result = pooling.effective(beliefs.filterKeys { it == 1L }, coef, ids, now = 0L)
        assertEquals(ln(100f), result.effective[1L]!!.mu, 1e-6f)
        assertEquals(0.01f, result.effective[1L]!!.sigma2, 1e-6f)
        // Belief-less siblings still get the full-pool (= A-only) prediction.
        assertEquals(ln(0.8f) + ln(100f), result.effective[2L]!!.mu, 1e-5f)
    }

    @Test
    fun coldMuscleHasNoLevelAndNoPredictions() {
        val result = pooling.effective(emptyMap(), coef, ids, now = 0L)
        assertNull(result.levelLn)
        assertFalse(result.effective.containsKey(3L))
    }

    @Test
    fun zeroCoefficientExercisesNeitherVoteNorReceive() {
        val result = pooling.effective(beliefs, coef + (2L to 0f), ids, now = 0L)
        assertFalse(result.effective.containsKey(2L))
        assertEquals(ln(100f) - ln(1.0f), result.levelLn!!, 1e-5f)  // level from A alone
    }

    @Test
    fun beliefsAreAgedBeforeVoting() {
        val aging = BeliefPooling(config.copy(qPerDay = 1e-3f))
        val tenDays = 10 * 24L * 60 * 60 * 1000
        val result = aging.effective(beliefs, coef, ids, now = tenDays)
        // A's own variance is aged from 0.01 → 0.02 before blending.
        val vB = ln(72f) - ln(0.8f)
        val predVar = 1f / (1f / (0.05f + 0.01f)) + 0.01f   // B aged to 0.05, tau²=0.01
        val pOwn = 1f / 0.02f
        val pSib = 1f / predVar
        assertEquals((pOwn * ln(100f) + pSib * vB) / (pOwn + pSib), result.effective[1L]!!.mu, 1e-5f)
    }
}
