package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class CrossTuningTest {

    private val config = BeliefConfig()

    private fun belief(e1rm: Float, uncertainty: Float) = Belief(bestGuessLn = ln(e1rm), uncertainty = uncertainty, updatedAt = 0L)

    @Test
    fun agreementIsZeroWhenExerciseMatchesLeaveOneOutPrediction() {
        // Exercise 2's own e1rm (30) exactly matches sibling 1's implied prediction (coef 0.3 * 100 = 30).
        val beliefs = mapOf(1L to belief(100f, 0.01f), 2L to belief(30f, 0.01f))
        val seed = mapOf(1L to 1.0f, 2L to 0.3f)
        val rows = computeCrossTuning(
            beliefs = beliefs,
            seedCoef = seed,
            namesById = mapOf(1L to "A", 2L to "B"),
            muscleExerciseIds = listOf(1L, 2L),
            now = 0L,
            config = config,
        )
        val b = rows.first { it.exerciseId == 2L }
        assertEquals(0f, b.agreement, 1e-3f)
    }

    @Test
    fun contributionShareFollowsPrecisionWeighting() {
        // Exercise 1 has tighter variance (more precise) than exercise 2 -> larger contribution share.
        val beliefs = mapOf(1L to belief(100f, 0.01f), 2L to belief(30f, 0.04f))
        val seed = mapOf(1L to 1.0f, 2L to 0.3f)
        val independenceVar = config.crossLiftIndependenceEstimate * config.crossLiftIndependenceEstimate
        val rows = computeCrossTuning(
            beliefs = beliefs,
            seedCoef = seed,
            namesById = mapOf(1L to "A", 2L to "B"),
            muscleExerciseIds = listOf(1L, 2L),
            now = 0L,
            config = config,
        )
        val w1 = 1f / (0.01f + independenceVar)
        val w2 = 1f / (0.04f + independenceVar)
        val totalW = w1 + w2
        val row1 = rows.first { it.exerciseId == 1L }
        val row2 = rows.first { it.exerciseId == 2L }
        assertEquals(w1 / totalW, row1.contribution, 1e-4f)
        assertEquals(w2 / totalW, row2.contribution, 1e-4f)
        assertTrue(row1.contribution > row2.contribution)
        assertEquals(1f, (row1.contribution + row2.contribution), 1e-4f)
    }
}
