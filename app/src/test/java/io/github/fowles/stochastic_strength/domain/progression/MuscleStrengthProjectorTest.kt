package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
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
        ExerciseBelief(ln(e1rm), sigma * sigma, at, evidenceVar = sigma * sigma)
    private fun cold(e1rm: Float, at: Long = 0L) = ExerciseBelief.seed(e1rm, at, config)

    @Test
    fun poolPrecisionRisesWithEvidenceAndTightness() {
        val cold = cold(100f)                                   // evidenceVar = σ_seed² = 0.0625
        assertEquals(1f / (0.0625f + 0.25f * 0.25f), projector.poolPrecision(cold, 0.25f), 1e-3f)
        val trainedOther = ExerciseBelief(4f, 0.0004f, 0L, evidenceVar = 0.0004f)
        assertTrue("trained beats cold", projector.poolPrecision(trainedOther, 0.25f) > projector.poolPrecision(cold, 0.25f))
        assertTrue("barbell τ gives higher precision than other-loaded",
            projector.poolPrecision(trainedOther, 0.08f) > projector.poolPrecision(trainedOther, 0.25f))
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
    fun coldBarbellAdoptsSiblingsWhileColdDumbbellPartiallyAdopts() {
        val seed = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 0.6f)
        val beliefs = mapOf(
            1L to trained(130f, days(30)),
            2L to trained(104f, days(30)),
            3L to cold(60f),                                   // sibling-implied ≈ 130 × 0.6 = 78
        )
        val barbell = projector.project(beliefs, seed, listOf(1L, 2L, 3L), now = days(30),
            equipment = mapOf(1L to Equipment.BARBELL, 2L to Equipment.BARBELL, 3L to Equipment.BARBELL))
        assertTrue("cold barbell should approach 78", abs(barbell.effectiveE1rm[3L]!! - 78f) / 78f <= 0.12f)

        val dumbbell = projector.project(beliefs, seed, listOf(1L, 2L, 3L), now = days(30),
            equipment = mapOf(1L to Equipment.BARBELL, 2L to Equipment.BARBELL, 3L to Equipment.DUMBBELL))
        val own3 = 60f
        assertTrue("cold dumbbell pulls up from own toward 78 but not all the way",
            dumbbell.effectiveE1rm[3L]!! in (own3 + 1f)..(78f - 1f))
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
