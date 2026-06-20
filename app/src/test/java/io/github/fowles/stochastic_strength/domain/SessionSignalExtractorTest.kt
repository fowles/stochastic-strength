package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSignalExtractorTest {

    private fun set(weight: Float, reps: Int, fb: SetFeedback?, actual: Int? = null) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = 1,
            targetWeight = weight, targetReps = reps, actualReps = actual, feedback = fb)

    @Test
    fun rir01_implies_one_rep_in_reserve() {
        val s = SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.RIR_0_1))!!
        assertEquals(DefaultProgressionEngine.toOneRepMax(100f, 6), s.est1RM, 1e-3f)
        assertEquals(0.85f, s.confidence, 1e-6f)
        assertTrue(!s.isUpperBound)
    }

    @Test
    fun too_hard_without_reps_assumes_half_target_reps_at_full_confidence() {
        val s = SessionSignalExtractor.setSignal(set(100f, 10, SetFeedback.TOO_HARD, actual = null))!!
        // No reps logged: assume half the target reps were achieved, at full confidence,
        // still treated as an upper bound (a failure can cap the estimate, never inflate it).
        assertEquals(DefaultProgressionEngine.toOneRepMax(100f, 5), s.est1RM, 1e-3f)
        assertEquals(0.95f, s.confidence, 1e-6f)
        assertTrue(s.isUpperBound)
    }

    @Test
    fun too_hard_without_reps_floors_to_one_rep() {
        val s = SessionSignalExtractor.setSignal(set(100f, 1, SetFeedback.TOO_HARD, actual = null))!!
        assertEquals(DefaultProgressionEngine.toOneRepMax(100f, 1), s.est1RM, 1e-3f)
    }

    @Test
    fun hurt_yields_no_signal() {
        assertNull(SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.HURT)))
    }

    @Test
    fun aggregate_confidence_weights_est1rm() {
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(set(100f, 5, SetFeedback.RIR_2_4), set(100f, 5, SetFeedback.RIR_0_1)),
        )!!
        // both non-upper-bound; weighted mean of the two implied 1RMs by confidence.
        val a = DefaultProgressionEngine.toOneRepMax(100f, 8) // RIR_2_4 -> +3
        val b = DefaultProgressionEngine.toOneRepMax(100f, 6) // RIR_0_1 -> +1
        val expected = (a * 0.7f + b * 0.85f) / (0.7f + 0.85f)
        assertEquals(expected, agg.est1RM, 1e-2f)
    }
}
