package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EstCoefConsensusHeuristicTest {

    private val heuristic = EstCoefConsensusHeuristic()

    private fun set(
        targetWeight: Float = 80f,
        targetReps: Int = 5,
        actualReps: Int? = null,
        feedback: SetFeedback? = null,
    ) = WorkoutSet(
        sessionId = 1L,
        exerciseId = 1L,
        setNumber = 1,
        targetWeight = targetWeight,
        targetReps = targetReps,
        actualReps = actualReps,
        feedback = feedback,
    )

    @Test
    fun setSignal_returnsNullForNullFeedback() {
        assertNull(heuristic.setSignal(set(feedback = null)))
    }

    @Test
    fun setSignal_returnsNullForHurt() {
        assertNull(heuristic.setSignal(set(feedback = SetFeedback.HURT)))
    }

    @Test
    fun setSignal_rir5Plus_isWeakSignal() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS))!!
        // toOneRepMax(80, 5 + 7 = 12)
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 12)
        assertEquals(expected, s.est1RM, 0.001f)
        assertEquals(0.4f, s.confidence, 0.001f)
        assertFalse(s.isUpperBound)
        assertFalse(s.isDefinite)
    }

    @Test
    fun setSignal_rir2_4_isMidConfidence() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 8)
        assertEquals(expected, s.est1RM, 0.001f)
        assertEquals(0.7f, s.confidence, 0.001f)
    }

    @Test
    fun setSignal_rir0_1_isHighConfidence() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 8, feedback = SetFeedback.RIR_0_1))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 9)
        assertEquals(expected, s.est1RM, 0.001f)
        assertEquals(0.85f, s.confidence, 0.001f)
    }

    @Test
    fun setSignal_tooHardWithActualReps_isDefinite() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 8, actualReps = 3, feedback = SetFeedback.TOO_HARD))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 3)
        assertEquals(expected, s.est1RM, 0.001f)
        assertEquals(0.95f, s.confidence, 0.001f)
        assertTrue(s.isDefinite)
        assertFalse(s.isUpperBound)
    }

    @Test
    fun setSignal_tooHardWithoutActualReps_isUpperBound() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 8, actualReps = null, feedback = SetFeedback.TOO_HARD))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 7)
        assertEquals(expected, s.est1RM, 0.001f)
        assertEquals(0.5f, s.confidence, 0.001f)
        assertTrue(s.isUpperBound)
        assertFalse(s.isDefinite)
    }

    @Test
    fun setSignal_tooHardWithoutActualReps_targetReps1_clampsTo1() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 1, actualReps = null, feedback = SetFeedback.TOO_HARD))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 1)
        assertEquals(expected, s.est1RM, 0.001f)
    }

    @Test
    fun aggregateSession_returnsNullForAllNullSets() {
        val agg = heuristic.aggregateSession(listOf(
            set(feedback = null),
            set(feedback = SetFeedback.HURT),
        ))
        assertNull(agg)
    }

    @Test
    fun aggregateSession_twoRir2_4Sets_returnsWeightedMean() {
        val sets = listOf(
            set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
        )
        val agg = heuristic.aggregateSession(sets)!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 8)
        assertEquals(expected, agg.est1RM, 0.001f)
        assertEquals(0.7f, agg.sessionConfidence, 0.001f)
        assertFalse(agg.hasDefinite)
    }

    @Test
    fun aggregateSession_includesReducedWeightSets() {
        // Original RIR_2_4 at 80 followed by reduced-weight RIR_0_1 at 70 (post-failure backoff).
        val sets = listOf(
            set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            set(targetWeight = 70f, targetReps = 5, feedback = SetFeedback.RIR_0_1),
        )
        val agg = heuristic.aggregateSession(sets)!!
        val a = DefaultProgressionEngine.toOneRepMax(80f, 8)
        val b = DefaultProgressionEngine.toOneRepMax(70f, 6)
        val expectedEst1RM = (a * 0.7f + b * 0.85f) / (0.7f + 0.85f)
        assertEquals(expectedEst1RM, agg.est1RM, 0.001f)
    }

    @Test
    fun aggregateSession_definiteFlagSetWhenAnySetIsTooHardWithActualReps() {
        val sets = listOf(
            set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            set(targetWeight = 75f, targetReps = 5, actualReps = 3, feedback = SetFeedback.TOO_HARD),
        )
        val agg = heuristic.aggregateSession(sets)!!
        assertTrue(agg.hasDefinite)
    }

    @Test
    fun aggregateSession_upperBoundOmittedWhenOtherPointsLower() {
        // The RIR_5_PLUS estimate at (60, 12) is much less than the upper bound at (100, 4) — upper bound
        // would dominate if included. Spec says omit it when other-feedback est_1RM is below the bound.
        val sets = listOf(
            set(targetWeight = 60f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS),
            set(targetWeight = 100f, targetReps = 5, actualReps = null, feedback = SetFeedback.TOO_HARD),
        )
        val agg = heuristic.aggregateSession(sets)!!
        val rirEst = DefaultProgressionEngine.toOneRepMax(60f, 12)
        assertEquals(rirEst, agg.est1RM, 0.001f)
    }

    @Test
    fun aggregateSession_upperBoundIncludedWhenOtherPointsAgreeAbove() {
        // Other-feedback est_1RM exceeds upper bound — bound is in agreement (below or equal) → included.
        val sets = listOf(
            set(targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS),
            set(targetWeight = 100f, targetReps = 5, actualReps = null, feedback = SetFeedback.TOO_HARD),
        )
        val agg = heuristic.aggregateSession(sets)!!
        val rirEst = DefaultProgressionEngine.toOneRepMax(100f, 12)
        val upperBound = DefaultProgressionEngine.toOneRepMax(100f, 4)
        // When upper bound is *below* the other-feedback estimate, it's a ceiling that pulls the agg down.
        // Confidence-weighted mean of (rirEst @ 0.4, upperBound @ 0.5).
        val expected = (rirEst * 0.4f + upperBound * 0.5f) / (0.4f + 0.5f)
        assertEquals(expected, agg.est1RM, 0.001f)
    }
}
