package io.github.fowles.stochastic_strength.domain.belief

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.policy.SetIntervals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetObservationTest {
    private val config = BeliefConfig()

    private fun set(weight: Float, reps: Int, feedback: SetFeedback?, actualReps: Int? = null) = WorkoutSet(
        sessionId = 1L,
        exerciseId = 1L,
        setNumber = 1,
        targetWeight = weight,
        targetReps = reps,
        actualReps = actualReps,
        feedback = feedback,
    )

    @Test
    fun rirSetYieldsFiniteIntervalMidpoint() {
        val s = set(weight = 100f, reps = 5, feedback = SetFeedback.RIR_0_1) // interval [1RM(w,5), 1RM(w,7)]
        val i = SetIntervals.impliedLn1RmInterval(s)!!
        assertEquals((i.lowerLn!! + i.upperLn!!) / 2f, setObservationLn(s, rank = 1, config)!!, 1e-6f)
    }

    @Test
    fun unboundedIntervalsUseTheirFiniteBound() {
        val easy = set(weight = 100f, reps = 5, feedback = SetFeedback.RIR_5_PLUS)   // [b, ∞)
        assertEquals(SetIntervals.impliedLn1RmInterval(easy)!!.lowerLn!!, setObservationLn(easy, 1, config)!!, 1e-6f)
        val fail = set(weight = 100f, reps = 5, feedback = SetFeedback.TOO_HARD, actualReps = null) // (−∞, b]
        assertEquals(SetIntervals.impliedLn1RmInterval(fail)!!.upperLn!!, setObservationLn(fail, 1, config)!!, 1e-6f)
    }

    @Test
    fun laterRanksAreFatigueCorrectedUpward() {
        val s = set(weight = 100f, reps = 5, feedback = SetFeedback.RIR_0_1)
        val fresh = setObservationLn(s, rank = 1, config)!!
        val third = setObservationLn(s, rank = 3, config)!!
        assertEquals(fresh + BeliefFold(config).fatigueShift(3), third, 1e-6f)
    }

    @Test
    fun hurtAndFeedbacklessSetsYieldNull() {
        assertNull(setObservationLn(set(100f, 5, SetFeedback.HURT), 1, config))
        assertNull(setObservationLn(set(100f, 5, feedback = null), 1, config))
    }
}
