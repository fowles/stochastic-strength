package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class CrossTuningTest {

    private fun est(e1rm: Float, conf: Float) = ExerciseEstimate(lnE = ln(e1rm), confidence = conf, updatedAt = 0L)

    @Test
    fun agreementIsPositiveWhenExerciseExceedsConsensus() {
        // Exercise 1 is stronger than its seed ratio vs sibling 2 implies → positive agreement.
        val estimates = mapOf(1L to est(120f, conf = 6f), 2L to est(60f, conf = 6f))
        val seed = mapOf(1L to 1.0f, 2L to 0.6f)
        val rows = computeCrossTuning(
            estimates = estimates,
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
    fun contributionsSumToOneAndColdExerciseIsNearZero() {
        val estimates = mapOf(1L to est(100f, conf = 6f), 2L to est(60f, conf = 0f))
        val seed = mapOf(1L to 1.0f, 2L to 0.6f)
        val rows = computeCrossTuning(
            estimates = estimates,
            seedCoef = seed,
            namesById = mapOf(1L to "A", 2L to "B"),
            muscleExerciseIds = listOf(1L, 2L),
            now = 0L,
        )
        val sum = rows.sumOf { it.contribution.toDouble() }.toFloat()
        assertEquals(1f, sum, 1e-3f)
        assertTrue(rows.first { it.exerciseId == 2L }.contribution < 0.05f)
    }
}
