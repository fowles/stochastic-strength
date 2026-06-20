package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastSetAutoregulationHeuristicTest {

    private val heuristic = LastSetAutoregulationHeuristic()

    private fun set(
        exerciseId: Long = 1L,
        setNumber: Int = 1,
        targetWeight: Float = 100f,
        targetReps: Int = 10,
        actualReps: Int? = null,
        feedback: SetFeedback? = null,
    ) = WorkoutSet(
        sessionId = 1L,
        exerciseId = exerciseId,
        setNumber = setNumber,
        targetWeight = targetWeight,
        targetReps = targetReps,
        actualReps = actualReps,
        feedback = feedback,
    )

    private fun input(
        sets: List<WorkoutSet>,
        currentBaselines: Map<MuscleGroup, Float> = mapOf(MuscleGroup.CHEST to 100f),
        exerciseMuscle: Map<Long, MuscleGroup> = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST),
        minReductionFractions: Map<MuscleGroup, Float> = emptyMap(),
        weightUnit: WeightUnit = WeightUnit.KG,
        currentCoefficients: Map<Long, Float> = mapOf(1L to 1.0f, 2L to 1.0f),
    ) = BaselineComputationInput(
        sets = sets,
        exerciseMuscle = exerciseMuscle,
        currentCoefficients = currentCoefficients,
        currentBaselines = currentBaselines,
        recentHistory = emptyMap<MuscleGroup, List<BaselineHistory>>(),
        sessionReps = 10,
        minReductionFractions = minReductionFractions,
        asOf = 1_000_000L,
        weightUnit = weightUnit,
    )

    @Test
    fun governingSet_mapsFeedbackToTargetPct() {
        assertEquals(0.15f, heuristic.exerciseTargetPct(listOf(set(feedback = SetFeedback.RIR_5_PLUS)))!!, 1e-6f)
        assertEquals(0.10f, heuristic.exerciseTargetPct(listOf(set(feedback = SetFeedback.RIR_2_4)))!!, 1e-6f)
        assertEquals(0.05f, heuristic.exerciseTargetPct(listOf(set(feedback = SetFeedback.RIR_0_1)))!!, 1e-6f)
    }

    @Test
    fun nearMissFailure_holds() {
        // target 10, got 9 → within nearMiss(1) → hold (0%).
        val s = set(targetReps = 10, actualReps = 9, feedback = SetFeedback.TOO_HARD)
        assertEquals(0f, heuristic.exerciseTargetPct(listOf(s))!!, 1e-6f)
    }

    @Test
    fun genuineFailure_decreases() {
        // target 10, got 6 → beyond nearMiss → -5%.
        val s = set(targetReps = 10, actualReps = 6, feedback = SetFeedback.TOO_HARD)
        assertEquals(-0.05f, heuristic.exerciseTargetPct(listOf(s))!!, 1e-6f)
    }

    @Test
    fun failureWithoutReps_holds() {
        val s = set(feedback = SetFeedback.TOO_HARD, actualReps = null)
        assertEquals(0f, heuristic.exerciseTargetPct(listOf(s))!!, 1e-6f)
    }

    @Test
    fun noFeedback_andHurt_andEmpty_contributeNothing() {
        assertNull(heuristic.exerciseTargetPct(listOf(set(feedback = null))))
        assertNull(heuristic.exerciseTargetPct(listOf(set(feedback = SetFeedback.HURT))))
        assertNull(heuristic.exerciseTargetPct(emptyList()))
    }

    @Test
    fun governingSet_isLastSetAtFullWeight() {
        // 3 sets, no reduction. Last set (RIR_0_1) governs, not earlier sets.
        val sets = listOf(
            set(setNumber = 1, feedback = SetFeedback.RIR_5_PLUS),
            set(setNumber = 2, feedback = SetFeedback.RIR_2_4),
            set(setNumber = 3, feedback = SetFeedback.RIR_0_1),
        )
        assertEquals(0.05f, heuristic.exerciseTargetPct(sets)!!, 1e-6f)
    }

    @Test
    fun reducedExercise_contributesNoSignal() {
        // Set 1 at full 100 failed, sets 2-3 dropped to 90 and hit target with reserve.
        val sets = listOf(
            set(setNumber = 1, targetWeight = 100f, targetReps = 10, actualReps = 7, feedback = SetFeedback.TOO_HARD),
            set(setNumber = 2, targetWeight = 90f, feedback = SetFeedback.RIR_2_4),
            set(setNumber = 3, targetWeight = 90f, feedback = SetFeedback.RIR_0_1),
        )
        assertNull(heuristic.exerciseTargetPct(sets))
    }

    @Test
    fun rir01_creepsOneIncrement_atHeavyBaseline() {
        // 5% of 100 kg = 5 kg → floor to 2.5 kg increments → 2 steps = 5 kg. B_new = 105.
        val s = set(targetWeight = 100f, feedback = SetFeedback.RIR_0_1)
        val r = heuristic.compute(input(listOf(s)))
        assertEquals(1, r.size)
        assertEquals(105f, r.single().newBaseline, 1e-4f)
    }

    @Test
    fun rir01_holds_atLightBaseline_belowFloor() {
        // 5% of 40 kg = 2.0 kg < 2.5 kg increment → floor to 0 → no proposal.
        val s = set(targetWeight = 40f, feedback = SetFeedback.RIR_0_1)
        val r = heuristic.compute(input(listOf(s), currentBaselines = mapOf(MuscleGroup.CHEST to 40f)))
        assertTrue(r.isEmpty())
    }

    @Test
    fun fatigueAcrossSets_doesNotPunish_holds() {
        // target 10 → 13,11,9 across 3 sets at full weight, no drop. Last set TOO_HARD/9
        // is a near-miss (within 1) → hold → no proposal. (The original bug: this used to drop.)
        val sets = listOf(
            set(setNumber = 1, targetReps = 10, feedback = SetFeedback.RIR_2_4),
            set(setNumber = 2, targetReps = 10, feedback = SetFeedback.RIR_0_1),
            set(setNumber = 3, targetReps = 10, actualReps = 9, feedback = SetFeedback.TOO_HARD),
        )
        val r = heuristic.compute(input(sets))
        assertTrue(r.isEmpty())
    }

    @Test
    fun hurt_overridesAndBacksOff() {
        val sets = listOf(
            set(setNumber = 1, feedback = SetFeedback.RIR_5_PLUS),
            set(setNumber = 2, feedback = SetFeedback.HURT),
        )
        // round(100 * 0.85) = round(85) = 85.
        val r = heuristic.compute(input(sets))
        assertEquals(85f, r.single().newBaseline, 1e-4f)
    }

    @Test
    fun reductionClamp_winsOverUpSignal() {
        // Clean RIR_5_PLUS (would be +15%) but the muscle was dropped 10% mid-session.
        // cap = round(100 * 0.90) = 90 → B_new clamped to 90.
        val s = set(targetWeight = 100f, feedback = SetFeedback.RIR_5_PLUS)
        val r = heuristic.compute(input(listOf(s), minReductionFractions = mapOf(MuscleGroup.CHEST to 0.10f)))
        assertEquals(90f, r.single().newBaseline, 1e-4f)
    }

    @Test
    fun twoExercises_averageTheirPercentages() {
        // Ex1 RIR_5_PLUS (+15%), Ex2 RIR_0_1 (+5%) → avg 10% of 100 = 10 kg → floor 2.5 → 10 kg. B_new=110.
        val sets = listOf(
            set(exerciseId = 1L, feedback = SetFeedback.RIR_5_PLUS),
            set(exerciseId = 2L, feedback = SetFeedback.RIR_0_1),
        )
        val r = heuristic.compute(input(sets))
        assertEquals(110f, r.single().newBaseline, 1e-4f)
    }

    @Test
    fun noSignal_noProposal() {
        val s = set(feedback = null)
        assertTrue(heuristic.compute(input(listOf(s))).isEmpty())
    }

    @Test
    fun easySet_belowBaselineWeight_contributesNoUpSignal() {
        // Baseline 100, coeff 1, 10 reps → the baseline prescribes ~83 kg.
        // A historical/backfilled set logged at 50 kg (well below) reading RIR_5_PLUS is
        // trivially easy and must NOT push the baseline up.
        val prescribed = DefaultProgressionEngine.fromOneRepMax(100f * 1.0f, 10)
        assertTrue("test premise: 50 kg is below prescribed", 50f < prescribed - 2.5f)
        val s = set(targetWeight = 50f, targetReps = 10, feedback = SetFeedback.RIR_5_PLUS)
        assertTrue(heuristic.compute(input(listOf(s))).isEmpty())
    }

    @Test
    fun easySet_atBaselineWeight_stillCountsAsUpSignal() {
        // The same easy set, logged at the baseline-prescribed weight, must still raise.
        val prescribed = DefaultProgressionEngine.fromOneRepMax(100f * 1.0f, 10)
        val s = set(targetWeight = prescribed, targetReps = 10, feedback = SetFeedback.RIR_5_PLUS)
        val r = heuristic.compute(input(listOf(s)))
        assertEquals(1, r.size)
        assertTrue("baseline should increase", r.single().newBaseline > 100f)
    }

    @Test
    fun zeroCoefficientExercise_contributesNoUpSignal() {
        // Bodyweight/banded/wall-sit moves have coefficient 0 and weight 0 — no relationship to
        // the loaded baseline. An easy bodyweight squat must NOT raise the loaded quad baseline.
        val s = set(exerciseId = 1L, targetWeight = 0f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS)
        val r = heuristic.compute(input(listOf(s), currentCoefficients = mapOf(1L to 0f)))
        assertTrue(r.isEmpty())
    }

    @Test
    fun zeroCoefficientExercise_contributesNoDownSignal() {
        // Likewise a "failed" bodyweight set must not drag the loaded baseline down.
        val s = set(exerciseId = 1L, targetWeight = 0f, targetReps = 5, actualReps = 2, feedback = SetFeedback.TOO_HARD)
        val r = heuristic.compute(input(listOf(s), currentCoefficients = mapOf(1L to 0f)))
        assertTrue(r.isEmpty())
    }

    @Test
    fun failureBelowBaselineWeight_stillDecreases() {
        // Down-signals are unconditional: failing even at a sub-baseline weight should drop.
        val s = set(targetWeight = 50f, targetReps = 10, actualReps = 6, feedback = SetFeedback.TOO_HARD)
        val r = heuristic.compute(input(listOf(s)))
        assertEquals(1, r.size)
        assertTrue("baseline should decrease", r.single().newBaseline < 100f)
    }
}
