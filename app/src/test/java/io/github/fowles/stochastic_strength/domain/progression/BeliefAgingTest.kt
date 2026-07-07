package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeliefAgingTest {
    private val config = EstimatorConfig()
    private val updater = BeliefUpdater(config)
    private fun days(d: Int): Long = d.toLong() * 24 * 60 * 60 * 1000
    private fun belief(sigma: Float, at: Long) = ExerciseBelief(mu = 4f, sigma2 = sigma * sigma, updatedAt = at)

    @Test
    fun varianceGrowsLinearlyWithIdleDaysAndClamps() {
        val b = belief(0.05f, at = 0L)
        val aged = updater.age(b, now = days(10), muscleLastObs = 0L)
        assertEquals(0.0025f + config.processNoisePerDay * 10f, aged.sigma2, 1e-6f)
        val long = updater.age(b, now = days(4000), muscleLastObs = 0L)
        assertEquals(config.sigmaMax * config.sigmaMax, long.sigma2, 1e-6f)
        assertEquals(days(4000), long.updatedAt)
    }

    @Test
    fun noDriftWithinGraceOrWhenMuscleTrainsElsewhere() {
        val b = belief(0.05f, at = 0L)
        // 10 days idle < 14-day grace: μ untouched.
        assertEquals(4f, updater.age(b, days(10), muscleLastObs = 0L).mu, 1e-6f)
        // Muscle trained (by a sibling) 2 days ago: drift window (recent+grace, now) is empty.
        assertEquals(4f, updater.age(b, days(60), muscleLastObs = days(58)).mu, 1e-6f)
        // Muscle never trained at all: no drift.
        assertEquals(4f, updater.age(b, days(60), muscleLastObs = null).mu, 1e-6f)
    }

    @Test
    fun driftAccruesPastGraceAndIsCapped() {
        val b = belief(0.05f, at = 0L)
        // 8 weeks idle: drift = rate × (56−14)/7 = 6 weeks × 1% = 0.06.
        val aged = updater.age(b, days(56), muscleLastObs = 0L)
        assertEquals(4f - config.detrainRatePerWeek * 6f, aged.mu, 1e-4f)
        // Multi-year gap: capped at detrainCap.
        val far = updater.age(b, days(1500), muscleLastObs = 0L)
        assertEquals(4f - config.detrainCap, far.mu, 1e-4f)
    }

    @Test
    fun overrideNewerThanMuscleLastReanchorsDrift() {
        // Belief re-anchored (override) at day 100; muscle last trained day 0.
        val b = belief(0.10f, at = days(100))
        // Window starts at max(updatedAt, muscleLast+grace) = day 100; 3 weeks past it.
        val aged = updater.age(b, days(121), muscleLastObs = 0L)
        assertEquals(4f - config.detrainRatePerWeek * 3f, aged.mu, 1e-4f)
    }

    @Test
    fun agingIsIdempotentInComposition() {
        // age(t0→t1) then (t1→t2) must equal age(t0→t2) when the drift window is contiguous.
        val b = belief(0.05f, at = 0L)
        val oneHop = updater.age(b, days(70), muscleLastObs = 0L)
        val twoHop = updater.age(updater.age(b, days(40), muscleLastObs = 0L), days(70), muscleLastObs = 0L)
        assertEquals(oneHop.mu, twoHop.mu, 1e-4f)
        assertEquals(oneHop.sigma2, twoHop.sigma2, 1e-6f)
        assertTrue(oneHop.mu < 4f)
    }
}
