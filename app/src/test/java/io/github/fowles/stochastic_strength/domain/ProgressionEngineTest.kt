package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.ExerciseState
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionEngineTest {

    private fun state(sets: Int = 3, consecutive: Int = 0) = ExerciseState(
        exerciseId = 1L,
        currentSets = sets,
        consecutiveRir5PlusSessions = consecutive,
    )

    // --- Baseline (weight) progression ---

    @Test
    fun baselineRir5PlusIncreasesWeight() {
        val result = ProgressionEngine.applyBaselineFeedback(60f, SetFeedback.RIR_5_PLUS)
        assertTrue(result > 60f)
    }

    @Test
    fun baselineRir2To4IncreasesWeightLessThanRir5Plus() {
        val rir5 = ProgressionEngine.applyBaselineFeedback(60f, SetFeedback.RIR_5_PLUS)
        val rir2 = ProgressionEngine.applyBaselineFeedback(60f, SetFeedback.RIR_2_4)
        assertTrue(rir2 in 60f..rir5)
    }

    @Test
    fun baselineRir0To1MaintainsWeight() {
        assertEquals(60f, ProgressionEngine.applyBaselineFeedback(60f, SetFeedback.RIR_0_1), 0.001f)
    }

    @Test
    fun baselineTooHardReducesWeight() {
        val result = ProgressionEngine.applyBaselineFeedback(60f, SetFeedback.TOO_HARD)
        assertTrue(result < 60f)
    }

    @Test
    fun baselineHurtReducesWeightMoreThanTooHard() {
        val tooHard = ProgressionEngine.applyBaselineFeedback(60f, SetFeedback.TOO_HARD)
        val hurt = ProgressionEngine.applyBaselineFeedback(60f, SetFeedback.HURT)
        assertTrue(hurt <= tooHard)
    }

    @Test
    fun computeNextBaselineEmptyFeedbackUnchanged() {
        assertEquals(60f, ProgressionEngine.computeNextBaseline(60f, emptyList()), 0.001f)
    }

    @Test
    fun computeNextBaselineHurtTakesPriority() {
        val feedbacks = listOf(SetFeedback.RIR_5_PLUS, SetFeedback.HURT, SetFeedback.RIR_2_4)
        val result = ProgressionEngine.computeNextBaseline(60f, feedbacks)
        assertTrue(result < 60f)
    }

    // --- Set-count progression ---

    @Test
    fun setsTooHardReducesSets() {
        val result = ProgressionEngine.applySetFeedback(state(sets = 3), SetFeedback.TOO_HARD)
        assertEquals(2, result.currentSets)
    }

    @Test
    fun setsNeverDropBelowMinimum() {
        val result = ProgressionEngine.applySetFeedback(state(sets = 2), SetFeedback.TOO_HARD)
        assertEquals(2, result.currentSets)
    }

    @Test
    fun setsIncreaseAfterConsecutiveRir5Sessions() {
        var s = state(sets = 3)
        repeat(ProgressionEngine.CONSECUTIVE_RIR5_FOR_SET_INCREASE) {
            s = ProgressionEngine.applySetFeedback(s, SetFeedback.RIR_5_PLUS)
        }
        assertEquals(4, s.currentSets)
    }

    @Test
    fun consecutiveResetsAfterSetIncrease() {
        var s = state(sets = 3)
        repeat(ProgressionEngine.CONSECUTIVE_RIR5_FOR_SET_INCREASE) {
            s = ProgressionEngine.applySetFeedback(s, SetFeedback.RIR_5_PLUS)
        }
        assertEquals(0, s.consecutiveRir5PlusSessions)
    }

    @Test
    fun setsNeverExceedMaximum() {
        var s = state(sets = 4)
        repeat(10) { s = ProgressionEngine.applySetFeedback(s, SetFeedback.RIR_5_PLUS) }
        assertEquals(4, s.currentSets)
    }

    @Test
    fun computeNextSetStateEmptyFeedbackUnchanged() {
        val s = state(sets = 3)
        assertEquals(s, ProgressionEngine.computeNextSetState(s, emptyList()))
    }

    // --- Muscle-group feedback aggregation ---

    @Test
    fun muscleGroupHurtWinsOverEverything() {
        val feedbacks = listOf(SetFeedback.RIR_5_PLUS, SetFeedback.HURT, SetFeedback.RIR_2_4)
        assertEquals(SetFeedback.HURT, ProgressionEngine.aggregateMuscleGroupFeedback(feedbacks))
    }

    @Test
    fun muscleGroupTooHardWinsOverPositive() {
        val feedbacks = listOf(SetFeedback.RIR_5_PLUS, SetFeedback.TOO_HARD, SetFeedback.RIR_5_PLUS)
        assertEquals(SetFeedback.TOO_HARD, ProgressionEngine.aggregateMuscleGroupFeedback(feedbacks))
    }

    @Test
    fun muscleGroupConservativePositive() {
        val feedbacks = listOf(SetFeedback.RIR_5_PLUS, SetFeedback.RIR_0_1, SetFeedback.RIR_5_PLUS)
        assertEquals(SetFeedback.RIR_0_1, ProgressionEngine.aggregateMuscleGroupFeedback(feedbacks))
    }

    @Test
    fun muscleGroupAllEasyIsEasy() {
        val feedbacks = listOf(SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS)
        assertEquals(SetFeedback.RIR_5_PLUS, ProgressionEngine.aggregateMuscleGroupFeedback(feedbacks))
    }

    // --- Rep/weight scaling ---

    @Test
    fun scaleWeightPreservesOneRepMax() {
        val weight10 = 60f
        val weight5 = ProgressionEngine.scaleWeight(weight10, fromReps = 10, toReps = 5)
        val backTo10 = ProgressionEngine.scaleWeight(weight5, fromReps = 5, toReps = 10)
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

    // --- WeightFormatter ---

    @Test
    fun weightFormatterRoundsToLbs() {
        assertEquals("220 lbs", WeightFormatter.format(100f, WeightUnit.LBS))
        assertEquals("226 lbs", WeightFormatter.format(102.5f, WeightUnit.LBS))
    }

    @Test
    fun weightFormatterRoundsToPlateIncrements() {
        val roundedToLbs = WeightFormatter.round(102.5f, WeightUnit.LBS)
        assertEquals(225f, roundedToLbs * 2.20462f, 0.1f)
        assertEquals(102.5f, WeightFormatter.round(103f, WeightUnit.KG), 0.001f)
    }
}
