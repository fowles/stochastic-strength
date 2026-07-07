package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

class MuscleStrengthProjectorTest {
    private val config = EstimatorConfig()
    private val projector = MuscleStrengthProjector(config)
    private fun days(d: Int): Long = d.toLong() * 24 * 60 * 60 * 1000
    private fun trained(e1rm: Float, at: Long, sigma: Float = 0.03f) =
        ExerciseBelief(ln(e1rm), sigma * sigma, at)
    private fun cold(e1rm: Float, at: Long = 0L) = ExerciseBelief.seed(e1rm, at, config)

    @Test
    fun neffScalesFromZeroAtSeedToTrainedRange() {
        assertEquals(0f, projector.neff(cold(100f)), 1e-6f)
        val full = projector.neff(ExerciseBelief(4f, config.sigmaMin * config.sigmaMin, 0L))
        assertTrue("trained neff $full should land in today's confidence range", full in 3f..7f)
        // Stale (σ² above seed²): clamped to zero, never negative.
        assertEquals(0f, projector.neff(ExerciseBelief(4f, config.sigmaMax * config.sigmaMax, 0L)), 1e-6f)
    }

    @Test
    fun coldMuscleProjectsTheSeedLevel() {
        val beliefs = mapOf(1L to cold(80f), 2L to cold(40f))
        val seed = mapOf(1L to 1.0f, 2L to 0.5f)
        val proj = projector.project(beliefs, seed, listOf(1L, 2L), now = 0L)
        assertEquals(80f, proj.level, 0.5f)
        assertEquals(80f, proj.effectiveE1rm[1L]!!, 0.5f)
        assertEquals(40f, proj.effectiveE1rm[2L]!!, 0.5f)
    }

    @Test
    fun coldExerciseWithTrainedSiblingsIsPredictedFromTheirLevel() {
        // Two siblings trained to 130-level truth; the cold third (seeded at 100-level) is pulled
        // to within 12% of the sibling-implied capacity (carried-forward spec §9 pin, on the MEAN).
        val seed = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 0.6f)
        val beliefs = mapOf(
            1L to trained(130f, days(30)),
            2L to trained(104f, days(30)),
            3L to cold(60f), // seeded at level 100 × 0.6
        )
        val proj = projector.project(beliefs, seed, listOf(1L, 2L, 3L), now = days(30))
        val predicted = proj.effectiveE1rm[3L]!!
        assertTrue("cold exercise $predicted should approach 78 (130×0.6)", abs(predicted - 78f) / 78f <= 0.12f)
    }

    @Test
    fun staleOrSameAgeSiblingsDoNotLiftAFreshBelief() {
        // Fresh weak measurement vs stronger same-age/older siblings: gate must hold (≤ +1%).
        val seed = mapOf(1L to 0.30f, 2L to 0.55f, 3L to 0.45f)
        val now = days(400)
        val beliefs = mapOf(
            1L to trained(17.35f, now - days(6), sigma = 0.03f),
            2L to trained(36.45f, now - days(6), sigma = 0.03f),
            3L to trained(26.92f, now - days(11), sigma = 0.03f),
        )
        val proj = projector.project(beliefs, seed, listOf(1L, 2L, 3L), now = now)
        val own = exp(beliefs.getValue(1L).mu)
        assertTrue(
            "fresh belief ${proj.effectiveE1rm[1L]} must not be pulled above own $own",
            proj.effectiveE1rm[1L]!! <= own * 1.01f,
        )
    }

    @Test
    fun staleLoneVoterDecaysTowardTheSeedAnchor() {
        // One exercise trained far above seed, then idle long enough for σ to grow past σ_seed:
        // its vote → 0, level falls back to the seed-anchored prior (its own aged opinion is the
        // anchor mean, so the LEVEL equals its aged opinion — but the SHRINK no longer moves it up).
        val seed = mapOf(1L to 1.0f)
        val stale = ExerciseBelief(ln(150f), 0.29f * 0.29f, updatedAt = 0L)
        val proj = projector.project(mapOf(1L to stale), seed, listOf(1L), now = days(600), muscleLastObs = 0L)
        // With zero vote and zero sibling excess, effective == own aged mean (drift applies via age).
        val agedMu = BeliefUpdater(config).age(stale, days(600), muscleLastObs = 0L).mu
        assertEquals(exp(agedMu), proj.effectiveE1rm[1L]!!, exp(agedMu) * 0.01f)
    }

    @Test
    fun pooledSigmaExposesTheOwnAgedUncertainty() {
        val beliefs = mapOf(1L to trained(100f, 0L, sigma = 0.05f))
        val proj = projector.project(beliefs, mapOf(1L to 1f), listOf(1L), now = days(10), muscleLastObs = 0L)
        val expected = BeliefUpdater(config).age(beliefs.getValue(1L), days(10), 0L).sigma
        assertEquals(expected, proj.pooledSigma[1L]!!, 1e-4f)
    }

    @Test
    fun driftLowersProjectionAfterAMuscleWideLayoff() {
        val beliefs = mapOf(1L to trained(100f, 0L))
        val rested = projector.project(beliefs, mapOf(1L to 1f), listOf(1L), now = days(70), muscleLastObs = 0L)
        val fresh = projector.project(beliefs, mapOf(1L to 1f), listOf(1L), now = days(70), muscleLastObs = days(69))
        assertTrue("idle-muscle projection ${rested.effectiveE1rm[1L]} must sit below active ${fresh.effectiveE1rm[1L]}",
            rested.effectiveE1rm[1L]!! < fresh.effectiveE1rm[1L]!!)
    }
}
