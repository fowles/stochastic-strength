package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
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
        assertEquals(expected, s.est1RM, 0.5f)
        assertEquals(0.4f, s.confidence, 0.001f)
        assertTrue(s.isUpperBound.not())
        assertTrue(s.isDefinite.not())
    }

    @Test
    fun setSignal_rir2_4_isMidConfidence() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 8)
        assertEquals(expected, s.est1RM, 0.5f)
        assertEquals(0.7f, s.confidence, 0.001f)
    }

    @Test
    fun setSignal_rir0_1_isHighConfidence() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 8, feedback = SetFeedback.RIR_0_1))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 9)
        assertEquals(expected, s.est1RM, 0.5f)
        assertEquals(0.85f, s.confidence, 0.001f)
    }

    @Test
    fun setSignal_tooHardWithActualReps_isDefinite() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 8, actualReps = 3, feedback = SetFeedback.TOO_HARD))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 3)
        assertEquals(expected, s.est1RM, 0.5f)
        assertEquals(0.95f, s.confidence, 0.001f)
        assertTrue(s.isDefinite)
        assertTrue(s.isUpperBound.not())
    }

    @Test
    fun setSignal_tooHardWithoutActualReps_isUpperBound() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 8, actualReps = null, feedback = SetFeedback.TOO_HARD))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 7)
        assertEquals(expected, s.est1RM, 0.5f)
        assertEquals(0.5f, s.confidence, 0.001f)
        assertTrue(s.isUpperBound)
        assertTrue(s.isDefinite.not())
    }

    @Test
    fun setSignal_tooHardWithoutActualReps_targetReps1_clampsTo1() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 1, actualReps = null, feedback = SetFeedback.TOO_HARD))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 1)
        assertEquals(expected, s.est1RM, 0.5f)
    }
}
