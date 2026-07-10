package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/**
 * Belief-based computeCrossTuning overload. Ports CrossTuningTest's scenarios one-for-one:
 * trained ≈ tight sigma, cold = seed belief; contribution is the exercise's precision share.
 * The estimate-based CrossTuningTest is deleted with the old overload in Task 5.
 */
class CrossTuningBeliefTest {

    private val config = EstimatorConfig()

    private fun trained(e1rm: Float, sigma: Float = 0.03f) =
        ExerciseBelief(mu = ln(e1rm), sigma2 = sigma * sigma, updatedAt = 0L, evidenceVar = sigma * sigma)

    private fun cold(e1rm: Float) = ExerciseBelief.seed(e1rm, at = 0L, config = config)

    @Test
    fun agreementIsPositiveWhenExerciseExceedsConsensus() {
        // Exercise 1 is stronger than its seed ratio vs sibling 2 implies → positive agreement.
        val beliefs = mapOf(1L to trained(120f), 2L to trained(60f))
        val seed = mapOf(1L to 1.0f, 2L to 0.6f)
        val rows = computeCrossTuning(
            beliefs = beliefs,
            seedCoef = seed,
            namesById = mapOf(1L to "A", 2L to "B"),
            muscleExerciseIds = listOf(1L, 2L),
            now = 0L,
        )
        val a = rows.first { it.exerciseId == 1L }
        // Sibling 2 (60 at seed 0.6) implies level ~100 → prediction for 1 ~100; own is 120 → +~0.2.
        assertTrue("agreement positive when above consensus", a.agreement > 0.1f)
    }

    @Test
    fun contributionsSumToOneAndColdExerciseCarriesLessThanTrained() {
        val beliefs = mapOf(1L to trained(100f), 2L to cold(60f))
        val seed = mapOf(1L to 1.0f, 2L to 0.6f)
        val rows = computeCrossTuning(
            beliefs = beliefs,
            seedCoef = seed,
            namesById = mapOf(1L to "A", 2L to "B"),
            muscleExerciseIds = listOf(1L, 2L),
            now = 0L,
        )
        val sum = rows.sumOf { it.contribution.toDouble() }.toFloat()
        assertEquals(1f, sum, 1e-3f)
        // Phase-3 pooling: contribution is the exercise's precision share 1/(evidenceVar+τ²). A cold
        // exercise still carries a real (nonzero) seed-floor precision, so it is a MINORITY of the
        // pool rather than ~zero — the trained exercise carries the larger share.
        val trainedShare = rows.first { it.exerciseId == 1L }.contribution
        val coldShare = rows.first { it.exerciseId == 2L }.contribution
        assertTrue("trained carries the larger precision share", trainedShare > coldShare)
        assertTrue("cold carries the minority share", coldShare < 0.5f)
    }
}
