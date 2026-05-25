package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.ExerciseState
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionEngineTest {

    private fun state(
        weight: Float = 60f,
        sets: Int = 3,
        reps: Int = 10,
        consecutive: Int = 0,
    ) = ExerciseState(
        exerciseId = 1L,
        currentWeight = weight,
        currentReps = reps,
        currentSets = sets,
        consecutiveRir5PlusSessions = consecutive,
    )

    @Test
    fun rir5PlusIncreasesWeight() {
        val result = ProgressionEngine.applyFeedback(state(60f), SetFeedback.RIR_5_PLUS)
        assertTrue(result.currentWeight > 60f)
    }

    @Test
    fun rir3To5IncreasesWeightLessThanRir5Plus() {
        val rir5 = ProgressionEngine.applyFeedback(state(60f), SetFeedback.RIR_5_PLUS)
        val rir3 = ProgressionEngine.applyFeedback(state(60f), SetFeedback.RIR_3_5)
        assertTrue(rir3.currentWeight in 60f..rir5.currentWeight)
    }

    @Test
    fun rir1To2MaintainsWeight() {
        val result = ProgressionEngine.applyFeedback(state(60f), SetFeedback.RIR_1_2)
        assertEquals(60f, result.currentWeight, 0.001f)
    }

    @Test
    fun tooHardReducesWeightAndSets() {
        val result = ProgressionEngine.applyFeedback(state(60f, sets = 3), SetFeedback.TOO_HARD)
        assertTrue(result.currentWeight < 60f)
        assertEquals(2, result.currentSets)
    }

    @Test
    fun hurtReducesWeightMoreThanTooHard() {
        val tooHard = ProgressionEngine.applyFeedback(state(60f), SetFeedback.TOO_HARD)
        val hurt = ProgressionEngine.applyFeedback(state(60f), SetFeedback.HURT)
        assertTrue(hurt.currentWeight <= tooHard.currentWeight)
    }

    @Test
    fun setsNeverDropBelowMinimum() {
        val result = ProgressionEngine.applyFeedback(state(60f, sets = 2), SetFeedback.TOO_HARD)
        assertEquals(2, result.currentSets)
    }

    @Test
    fun setsIncreaseAfterConsecutiveRir5Sessions() {
        var s = state(sets = 3)
        repeat(ProgressionEngine.CONSECUTIVE_RIR5_FOR_SET_INCREASE) {
            s = ProgressionEngine.applyFeedback(s, SetFeedback.RIR_5_PLUS)
        }
        assertEquals(4, s.currentSets)
    }

    @Test
    fun consecutiveResetsAfterSetIncrease() {
        var s = state(sets = 3)
        repeat(ProgressionEngine.CONSECUTIVE_RIR5_FOR_SET_INCREASE) {
            s = ProgressionEngine.applyFeedback(s, SetFeedback.RIR_5_PLUS)
        }
        assertEquals(0, s.consecutiveRir5PlusSessions)
    }

    @Test
    fun setsNeverExceedMaximum() {
        var s = state(sets = 4)
        repeat(10) { s = ProgressionEngine.applyFeedback(s, SetFeedback.RIR_5_PLUS) }
        assertEquals(4, s.currentSets)
    }

    @Test
    fun computeNextStateHurtTakesPriority() {
        val feedbacks = listOf(SetFeedback.RIR_5_PLUS, SetFeedback.HURT, SetFeedback.RIR_3_5)
        val result = ProgressionEngine.computeNextState(state(60f), feedbacks)
        assertTrue("HURT should reduce weight", result.currentWeight < 60f)
    }

    @Test
    fun computeNextStateTooHardTakesPriorityOverRir() {
        val feedbacks = listOf(SetFeedback.RIR_5_PLUS, SetFeedback.TOO_HARD, SetFeedback.RIR_5_PLUS)
        val result = ProgressionEngine.computeNextState(state(60f), feedbacks)
        assertTrue("TOO_HARD should reduce weight", result.currentWeight < 60f)
    }

    @Test
    fun zeroWeightExerciseWeightUnchanged() {
        val result = ProgressionEngine.applyFeedback(state(weight = 0f), SetFeedback.RIR_5_PLUS)
        assertEquals(0f, result.currentWeight, 0.001f)
    }

    @Test
    fun weightAlwaysRoundsToPlateIncrement() {
        val result = ProgressionEngine.applyFeedback(state(60f), SetFeedback.RIR_5_PLUS)
        assertEquals(0f, result.currentWeight % 2.5f, 0.001f)
    }

    @Test
    fun emptyFeedbackListReturnsUnchangedState() {
        val s = state(60f)
        val result = ProgressionEngine.computeNextState(s, emptyList())
        assertEquals(s, result)
    }
}
