package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.ln

class SetIntervalsTest {

    private fun set(feedback: SetFeedback?, w: Float = 100f, r: Int = 5, a: Int? = null) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = w, targetReps = r, actualReps = a, feedback = feedback)

    private fun cap(w: Float, reps: Float) = ln(DefaultProgressionEngine.rawToOneRepMax(w, reps))

    @Test
    fun rir01IsTwoSided() {
        val i = SetIntervals.impliedLn1RmInterval(set(SetFeedback.RIR_0_1))!!
        assertEquals(cap(100f, 5f), i.lowerLn!!, 1e-6f)
        assertEquals(cap(100f, 7f), i.upperLn!!, 1e-6f)
    }

    @Test
    fun rir24IsTwoSided() {
        val i = SetIntervals.impliedLn1RmInterval(set(SetFeedback.RIR_2_4))!!
        assertEquals(cap(100f, 7f), i.lowerLn!!, 1e-6f)
        assertEquals(cap(100f, 10f), i.upperLn!!, 1e-6f)
    }

    @Test
    fun rir5PlusIsLowerBoundOnly() {
        val i = SetIntervals.impliedLn1RmInterval(set(SetFeedback.RIR_5_PLUS))!!
        assertEquals(cap(100f, 10f), i.lowerLn!!, 1e-6f)
        assertNull(i.upperLn)
    }

    @Test
    fun countedFailureIsNarrowAroundDemonstratedReps() {
        val i = SetIntervals.impliedLn1RmInterval(set(SetFeedback.TOO_HARD, a = 3))!!
        assertEquals(cap(100f, 3f), i.lowerLn!!, 1e-6f)
        assertEquals(cap(100f, 4f), i.upperLn!!, 1e-6f)
    }

    @Test
    fun uncountedFailureIsUpperBoundOnly() {
        val i = SetIntervals.impliedLn1RmInterval(set(SetFeedback.TOO_HARD))!!
        assertNull(i.lowerLn)
        assertEquals(cap(100f, 5f), i.upperLn!!, 1e-6f)
    }

    @Test
    fun hurtNullFeedbackAndZeroWeightAreNotScored() {
        assertNull(SetIntervals.impliedLn1RmInterval(set(SetFeedback.HURT)))
        assertNull(SetIntervals.impliedLn1RmInterval(set(null)))
        assertNull(SetIntervals.impliedLn1RmInterval(set(SetFeedback.RIR_0_1, w = 0f)))
    }

    @Test
    fun distanceIsZeroInsideAndLinearOutside() {
        val i = LnInterval(lowerLn = 1f, upperLn = 2f)
        assertEquals(0f, i.distanceTo(1.5f), 0f)
        assertEquals(0f, i.distanceTo(1f), 0f)
        assertEquals(0.5f, i.distanceTo(0.5f), 1e-6f)
        assertEquals(1f, i.distanceTo(3f), 1e-6f)
        assertEquals(0f, LnInterval(null, 2f).distanceTo(-100f), 0f)
        assertEquals(0f, LnInterval(1f, null).distanceTo(100f), 0f)
    }
}
