package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InferRepsTest {

    @Test
    fun roundTrip_recoversCompletedReps_forAllTargetsAndCandidates() {
        for (targetReps in DefaultProgressionEngine.REP_OPTIONS) {
            for (completed in 1 until targetReps) {
                for (fromWeight in listOf(80f, 100f, 120f, 150f)) {
                    val rounded = WeightFormatter.round(
                        DefaultProgressionEngine.scaleReps(
                            fromWeight,
                            from = maxOf(1, completed),
                            to = targetReps,
                        ),
                        WeightUnit.KG,
                    )
                    val inferred = inferReps(fromWeight, rounded, targetReps, WeightUnit.KG)
                    assertEquals(
                        "from=$fromWeight target=$targetReps completed=$completed observed=$rounded",
                        completed,
                        inferred,
                    )
                }
            }
        }
    }

    @Test
    fun unmatchedWeightDrop_returnsNull() {
        // A weight that does not correspond to any scaleReps projection (drop is supposed to lower
        // weight, and 99.5 isn't even a scaleReps output of 60 → anything).
        val result = inferReps(from = 60f, to = 99.5f, targetReps = 5, weightUnit = WeightUnit.KG)
        assertNull(result)
    }

    @Test
    fun prefersHigherRepsOnTie() {
        // Whenever two consecutive candidates round to the same weight, the higher one wins.
        // If no tie exists in the chosen (from, target), the round-trip test already covers
        // exact-match correctness — this test simply has nothing to catch.
        val target = 10
        val from = 60f
        for (c in 0 until target - 1) {
            val wLow = WeightFormatter.round(
                DefaultProgressionEngine.scaleReps(from, from = maxOf(1, c), to = target),
                WeightUnit.KG,
            )
            val wHigh = WeightFormatter.round(
                DefaultProgressionEngine.scaleReps(from, from = maxOf(1, c + 1), to = target),
                WeightUnit.KG,
            )
            if (wLow == wHigh) {
                val inferred = inferReps(from = from, to = wLow, targetReps = target, weightUnit = WeightUnit.KG)
                assertEquals("tie at c=$c vs c+1=${c + 1}, expected higher", c + 1, inferred)
            }
        }
    }
}
