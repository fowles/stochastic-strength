package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.ExerciseState
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
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
    fun rir2To4IncreasesWeightLessThanRir5Plus() {
        val rir5 = ProgressionEngine.applyFeedback(state(60f), SetFeedback.RIR_5_PLUS)
        val rir2 = ProgressionEngine.applyFeedback(state(60f), SetFeedback.RIR_2_4)
        assertTrue(rir2.currentWeight in 60f..rir5.currentWeight)
    }

    @Test
    fun rir0To1MaintainsWeight() {
        val result = ProgressionEngine.applyFeedback(state(60f), SetFeedback.RIR_0_1)
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
        val feedbacks = listOf(SetFeedback.RIR_5_PLUS, SetFeedback.HURT, SetFeedback.RIR_2_4)
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
    fun scaleWeightPreservesOneRepMax() {
        val weight10 = 60f
        val weight5 = ProgressionEngine.scaleWeight(weight10, fromReps = 10, toReps = 5)
        val backTo10 = ProgressionEngine.scaleWeight(weight5, fromReps = 5, toReps = 10)
        // Round-tripping through Epley should recover the original within one internal increment
        assertEquals(weight10, backTo10, 0.5f)
        assertTrue("5-rep weight should be heavier than 10-rep weight", weight5 > weight10)
    }

    @Test
    fun scaleWeightNoOpWhenSameReps() {
        assertEquals(60f, ProgressionEngine.scaleWeight(60f, fromReps = 10, toReps = 10), 0.001f)
    }

    @Test
    fun scaleWeightZeroWeightUnchanged() {
        assertEquals(0f, ProgressionEngine.scaleWeight(0f, fromReps = 10, toReps = 5), 0.001f)
    }

    @Test
    fun emptyFeedbackListReturnsUnchangedState() {
        val s = state(60f)
        val result = ProgressionEngine.computeNextState(s, emptyList())
        assertEquals(s, result)
    }

    @Test
    fun weightFormatterRoundsToLbs() {
        // 100 kg is 220.462 lbs. Should round to 220 lbs.
        assertEquals("220 lbs", WeightFormatter.format(100f, WeightUnit.LBS))
        
        // 102.5 kg is 225.97 lbs. Should round to 226 lbs in format (0 decimal).
        // Wait, 102.5kg is exactly 225.973... lbs.
        // Actually my format string was %.0f.
        assertEquals("226 lbs", WeightFormatter.format(102.5f, WeightUnit.LBS))
    }

    @Test
    fun weightFormatterRoundsToPlateIncrements() {
        // LBS mode should round 102.5kg (~226 lbs) to 225 lbs (next 5lb increment)
        // 225 lbs = 102.058... kg
        val roundedToLbs = WeightFormatter.round(102.5f, WeightUnit.LBS)
        assertEquals(225f, roundedToLbs * 2.20462f, 0.1f)
        
        // KG mode should round 103f to 102.5f (next 2.5kg increment)
        assertEquals(102.5f, WeightFormatter.round(103f, WeightUnit.KG), 0.001f)
    }
}
