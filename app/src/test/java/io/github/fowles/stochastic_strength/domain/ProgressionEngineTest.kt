package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionEngineTest {

    // --- Score calculation ---

    @Test
    fun scoreAllRir5Plus() {
        val score = ProgressionEngine.scoreFromFeedbacks(listOf(SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS))
        assertEquals(3.0f, score!!, 0.001f)
    }

    @Test
    fun scoreAllTooHard() {
        val score = ProgressionEngine.scoreFromFeedbacks(listOf(SetFeedback.TOO_HARD, SetFeedback.TOO_HARD, SetFeedback.TOO_HARD))
        assertEquals(-2.0f, score!!, 0.001f)
    }

    @Test
    fun scoreMixed_5Plus5Plus2_4() {
        val score = ProgressionEngine.scoreFromFeedbacks(listOf(SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS, SetFeedback.RIR_2_4))
        assertEquals(8f / 3f, score!!, 0.001f)
    }

    @Test
    fun scoreHurtExcludedFromAverage() {
        val score = ProgressionEngine.scoreFromFeedbacks(listOf(SetFeedback.RIR_5_PLUS, SetFeedback.HURT, SetFeedback.RIR_5_PLUS))
        assertEquals(3.0f, score!!, 0.001f)
    }

    @Test
    fun scoreAllHurtReturnsNull() {
        assertNull(ProgressionEngine.scoreFromFeedbacks(listOf(SetFeedback.HURT, SetFeedback.HURT)))
    }

    @Test
    fun scoreEmptyReturnsNull() {
        assertNull(ProgressionEngine.scoreFromFeedbacks(emptyList()))
    }

    // --- Band progression ---

    @Test
    fun band1_HighScoreIncreasesMoreThanBand2() {
        // "5+, 5+, 2-4" (avg=2.67, band1) should produce a larger result than "2-4, 2-4, 2-4" (avg=2.0, band2)
        val band1 = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS, SetFeedback.RIR_2_4))
        val band2 = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.RIR_2_4, SetFeedback.RIR_2_4, SetFeedback.RIR_2_4))
        assertTrue("band1 result $band1 should exceed band2 result $band2", band1 > band2)
    }

    @Test
    fun band2_AllRir24Increases() {
        val result = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.RIR_2_4, SetFeedback.RIR_2_4, SetFeedback.RIR_2_4))
        assertTrue(result > 60f)
    }

    @Test
    fun band3_AllRir01NowIncreases() {
        // Old behavior was "hold"; new behavior is a small increase
        val result = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.RIR_0_1, SetFeedback.RIR_0_1, SetFeedback.RIR_0_1))
        assertTrue("0-1 x3 should now increase baseline", result > 60f)
    }

    @Test
    fun band3_FloorEnforced_LowWeight() {
        // At 20 kg, 1.025x = 20.5, just at the floor — must be at least 20.5
        val result = ProgressionEngine.computeNextBaseline(20f, listOf(SetFeedback.RIR_0_1, SetFeedback.RIR_0_1, SetFeedback.RIR_0_1))
        assertTrue("floor should ensure increase at low weight", result >= 20.5f)
    }

    @Test
    fun band1_FloorEnforced_LowWeight() {
        // At 20 kg, floor is +2.5 kg regardless of factor
        val result = ProgressionEngine.computeNextBaseline(20f, listOf(SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS))
        assertTrue("band1 floor should be +2.5 kg at low weight", result >= 22.5f)
    }

    @Test
    fun band4_MixedHolds() {
        // "0-1, 0-1, TOO_HARD": avg = (1+1-2)/3 = 0.0 → hold
        val result = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.RIR_0_1, SetFeedback.RIR_0_1, SetFeedback.TOO_HARD))
        assertEquals(60f, result, 0.001f)
    }

    @Test
    fun band5_MixedNegativeReduces() {
        // "TOO_HARD, TOO_HARD, RIR_2_4": avg = (-2-2+2)/3 = -0.67 → band5 (small reduction)
        val result = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.TOO_HARD, SetFeedback.TOO_HARD, SetFeedback.RIR_2_4))
        assertTrue(result < 60f)
    }

    @Test
    fun band6_AllTooHardReducesMore() {
        val band5result = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.TOO_HARD, SetFeedback.TOO_HARD, SetFeedback.RIR_2_4))
        val band6result = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.TOO_HARD, SetFeedback.TOO_HARD, SetFeedback.TOO_HARD))
        assertTrue("band6 (all TOO_HARD) should reduce more than band5", band6result < band5result)
    }

    // --- 10-rep mixed leniency ---

    @Test
    fun tenRep_mixedWithAnySuccess_noChange() {
        // At 10 reps, any mix of success + TOO_HARD → no change (accumulated fatigue, not overload)
        val results = listOf(
            ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.RIR_0_1, SetFeedback.TOO_HARD, SetFeedback.TOO_HARD), sessionReps = 10),
            ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.RIR_2_4, SetFeedback.TOO_HARD, SetFeedback.TOO_HARD), sessionReps = 10),
            ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.RIR_5_PLUS, SetFeedback.TOO_HARD, SetFeedback.TOO_HARD), sessionReps = 10),
            ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.RIR_0_1, SetFeedback.RIR_0_1, SetFeedback.TOO_HARD), sessionReps = 10),
        )
        results.forEach { assertEquals("expected no change at 60f, got $it", 60f, it, 0.001f) }
    }

    @Test
    fun tenRep_allTooHard_stillReduces() {
        val result = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.TOO_HARD, SetFeedback.TOO_HARD, SetFeedback.TOO_HARD), sessionReps = 10)
        assertTrue("all TOO_HARD at 10 reps should still reduce", result < 60f)
    }

    @Test
    fun fiveRep_mixedNegative_stillReduces() {
        // At 5 reps, the same mixed feedback that's lenient at 10 reps still penalizes
        val result = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.RIR_0_1, SetFeedback.TOO_HARD, SetFeedback.TOO_HARD), sessionReps = 5)
        assertTrue("mixed TOO_HARD at 5 reps should still reduce", result < 60f)
    }

    // --- HURT override ---

    @Test
    fun computeNextBaselineEmptyFeedbackUnchanged() {
        assertEquals(60f, ProgressionEngine.computeNextBaseline(60f, emptyList()), 0.001f)
    }

    @Test
    fun computeNextBaselineHurtTakesPriority() {
        val result = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.RIR_5_PLUS, SetFeedback.HURT, SetFeedback.RIR_2_4))
        assertTrue(result < 60f)
    }

    @Test
    fun hurtOverridesTrumpsHighScore() {
        val hurtResult = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.HURT, SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS))
        val band1Result = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS))
        assertTrue("HURT override should reduce, not increase", hurtResult < 60f)
        assertTrue("HURT override should produce a much lower result than band1", hurtResult < band1Result)
    }

    // --- Muscle-group normalization ---

    @Test
    fun muscleGroup_6SetsNormalize() {
        // 6x RIR_5_PLUS should hit band1, same as 3x RIR_5_PLUS
        val threeSet = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS))
        val sixSet = ProgressionEngine.computeNextBaseline(60f, listOf(SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS))
        assertEquals("normalized score should produce same result regardless of set count", threeSet, sixSet, 0.001f)
    }

    @Test
    fun muscleGroup_HurtAnywhereOverrides() {
        val result = ProgressionEngine.computeNextBaseline(60f, listOf(
            SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS, SetFeedback.RIR_5_PLUS,
            SetFeedback.RIR_5_PLUS, SetFeedback.HURT, SetFeedback.RIR_5_PLUS,
        ))
        assertTrue("HURT anywhere in 6-set list should still trigger override", result < 60f)
    }

    // --- Rep/weight scaling ---

    @Test
    fun scaleWeightPreservesOneRepMax() {
        val weight10 = 60f
        val weight5 = ProgressionEngine.scaleReps(weight10, from = 10, to = 5)
        val backTo10 = ProgressionEngine.scaleReps(weight5, from = 5, to = 10)
        assertEquals(weight10, backTo10, 0.5f)
        assertTrue("5-rep weight should be heavier than 10-rep weight", weight5 > weight10)
    }

    @Test
    fun scaleWeightNoOpWhenSameReps() {
        assertEquals(60f, ProgressionEngine.scaleReps(60f, from = 10, to = 10), 0.001f)
    }

    @Test
    fun scaleWeightZeroWeightUnchanged() {
        assertEquals(0f, ProgressionEngine.scaleReps(0f, from = 10, to = 5), 0.001f)
    }

    @Test
    fun rawInversesAreAccurate() {
        for (weight in listOf(20f, 60f, 100f, 142.5f)) {
            for (reps in ProgressionEngine.REP_OPTIONS) {
                val orm      = ProgressionEngine.rawToOneRepMax(weight, reps)
                val restored = ProgressionEngine.rawFromOneRepMax(orm, reps)
                assertEquals("rawFrom(rawTo($weight, $reps))", weight, restored, 0.01f)
            }
        }
    }

    @Test
    fun scaleRepsRoundtripIsAccurate() {
        // scaleReps uses raw (unrounded) intermediate 1RM, so a from→to→from roundtrip
        // accumulates at most one rounding step of error.
        for (weight in listOf(20f, 60f, 100f, 142.5f)) {
            for (from in ProgressionEngine.REP_OPTIONS) {
                for (to in ProgressionEngine.REP_OPTIONS) {
                    val scaled   = ProgressionEngine.scaleReps(weight, from = from, to = to)
                    val restored = ProgressionEngine.scaleReps(scaled,  from = to,   to = from)
                    assertEquals("roundtrip scaleReps($weight, $from→$to→$from)", weight, restored, 0.5f)
                }
            }
        }
    }

    @Test
    fun roundedApiMatchesRawWithinHalfKg() {
        // fromOneRepMax(toOneRepMax(w, from), to) rounds the intermediate 1RM, so it may
        // differ from scaleReps (which keeps the 1RM unrounded) by up to 0.5 kg.
        for (weight in listOf(20f, 60f, 100f, 142.5f)) {
            for (from in ProgressionEngine.REP_OPTIONS) {
                for (to in ProgressionEngine.REP_OPTIONS) {
                    val viaRounded = ProgressionEngine.fromOneRepMax(ProgressionEngine.toOneRepMax(weight, from), to)
                    val viaRaw     = ProgressionEngine.scaleReps(weight, from = from, to = to)
                    assertEquals("fromOneRepMax(toOneRepMax($weight, $from), $to)", viaRaw, viaRounded, 0.5f)
                }
            }
        }
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
