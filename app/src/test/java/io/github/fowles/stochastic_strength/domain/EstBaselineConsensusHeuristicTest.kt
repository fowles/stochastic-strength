package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EstBaselineConsensusHeuristicTest {

    private val heuristic = EstBaselineConsensusHeuristic()

    private fun set(
        exerciseId: Long = 1L,
        targetWeight: Float = 80f,
        targetReps: Int = 5,
        actualReps: Int? = null,
        feedback: SetFeedback? = null,
    ) = WorkoutSet(
        sessionId = 1L,
        exerciseId = exerciseId,
        setNumber = 1,
        targetWeight = targetWeight,
        targetReps = targetReps,
        actualReps = actualReps,
        feedback = feedback,
    )

    private fun input(
        sets: List<WorkoutSet>,
        currentBaselines: Map<MuscleGroup, Float> = mapOf(MuscleGroup.CHEST to 100f),
        currentCoefficients: Map<Long, Float> = mapOf(1L to 1.0f),
        exerciseMuscle: Map<Long, MuscleGroup> = mapOf(1L to MuscleGroup.CHEST),
        recentHistory: Map<MuscleGroup, List<BaselineHistory>> = emptyMap(),
        minReductionFractions: Map<MuscleGroup, Float> = emptyMap(),
        sessionReps: Int = 5,
        asOf: Long = 1_000_000L,
        weightUnit: WeightUnit = WeightUnit.KG,
    ) = BaselineComputationInput(
        sets = sets,
        exerciseMuscle = exerciseMuscle,
        currentCoefficients = currentCoefficients,
        currentBaselines = currentBaselines,
        recentHistory = recentHistory,
        sessionReps = sessionReps,
        minReductionFractions = minReductionFractions,
        asOf = asOf,
        weightUnit = weightUnit,
    )

    @Test
    fun rir5Plus_singleSet_proposesUpStep() {
        // RIR_5_PLUS at 80×5 → est1RM = toOneRepMax(80, 12), impliedBaseline = est1RM / coef.
        // raw = 0.3 * 0.4 * ln(impliedBaseline / 100). Verify upward movement when impliedBaseline > 100.
        val s = set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS)
        val result = heuristic.compute(input(sets = listOf(s)))
        assertEquals(1, result.size)
        val proposal = result.single()
        assertTrue("baseline should move up, was ${proposal.newBaseline}", proposal.newBaseline > 100f)
        assertTrue("baseline should remain within sane bounds", proposal.newBaseline <= 105f)
    }

    @Test
    fun upperBound_droppedWhenNonUpperBoundMeanExceeds() {
        // Two sets in the same muscle group:
        // - TOO_HARD without actualReps at 80×8 → est1RM = toOneRepMax(80, 7) (upper bound).
        // - RIR_2_4 at 100×5 → est1RM = toOneRepMax(100, 8) (non-upper-bound, higher value).
        // The non-upper-bound mean exceeds the upper bound's implied value → upper bound is dropped.
        val sets = listOf(
            set(exerciseId = 1L, targetWeight = 80f, targetReps = 8, feedback = SetFeedback.TOO_HARD),
            set(exerciseId = 1L, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
        )
        val result = heuristic.compute(input(sets = sets))
        assertEquals(1, result.size)
        val proposal = result.single()
        // After dropping the upper bound, target ≈ 123.7. raw = 0.3 * 0.7 * ln(123.7/100) ≈ 0.0446.
        // upCap = ln(1.025) ≈ 0.0247 → clamped at 0.0247. B_new = 100 * 1.025 = 102.5.
        assertEquals(102.5f, proposal.newBaseline, 0.0001f)
    }

    @Test
    fun strongDownSignal_doesNotBindDownCap() {
        // TOO_HARD with actualReps=2 at 80×8 → est1RM = toOneRepMax(80, 2) ≈ 84.27.
        // raw = 0.3 * 0.95 * ln(84.27/100) ≈ -0.0489.
        // downCap = ln(1.10) ≈ 0.0953 → no bind. B_new = 100 * exp(-0.0489) ≈ 95.23 → rounds to 95.0.
        val s = set(targetWeight = 80f, targetReps = 8, actualReps = 2, feedback = SetFeedback.TOO_HARD)
        val result = heuristic.compute(input(sets = listOf(s)))
        val proposal = result.single()
        assertEquals(95f, proposal.newBaseline, 0.0001f)
    }

    @Test
    fun hurt_shortCircuitsTo85Percent() {
        val result = heuristic.compute(input(sets = listOf(
            set(feedback = SetFeedback.RIR_2_4),
            set(feedback = SetFeedback.HURT),
        )))
        assertEquals(1, result.size)
        val proposal = result.single()
        assertEquals(MuscleGroup.CHEST, proposal.muscleGroup)
        // round(100 * 0.85, KG) = round(85.0) = 85.0
        assertEquals(85f, proposal.newBaseline, 0.0001f)
        assertEquals("hurt", proposal.metadata)
    }

    @Test
    fun floorFires_whenCapBindsAndRoundingZeros() {
        // B_old = 20 kg, confident large-up signal. Up cap ≈ 2.5% → raw post-cap = 0.5 kg → rounds to 0
        // → floor fires → B_new = 22.5.
        val s = set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS)
        val result = heuristic.compute(input(
            sets = listOf(s),
            currentBaselines = mapOf(MuscleGroup.CHEST to 20f),
        ))
        val proposal = result.single()
        assertEquals(22.5f, proposal.newBaseline, 0.0001f)
    }

    @Test
    fun floorDoesNotFire_whenRawIsSmallEnoughToBeInCap() {
        // RIR_2_4 at 80×8 → est1RM = toOneRepMax(80, 11). Choose values so raw < up cap.
        // raw = 0.3 * 0.7 * ln(est1RM/100) ≈ 0.0211 (below upCap 0.0247) → not bound.
        // B_new = 100 * exp(0.0211) ≈ 102.13 → rounds to 102.5. Floor must NOT fire.
        val s = set(targetWeight = 80f, targetReps = 8, feedback = SetFeedback.RIR_2_4)
        val result = heuristic.compute(input(sets = listOf(s)))
        val proposal = result.single()
        assertEquals(102.5f, proposal.newBaseline, 0.0001f)
    }

    @Test
    fun minReductionFraction_capsResult() {
        // Strong up signal would propose 102.5, but minReductionFractions[CHEST] = 0.05 caps at 95.
        val s = set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS)
        val result = heuristic.compute(input(
            sets = listOf(s),
            minReductionFractions = mapOf(MuscleGroup.CHEST to 0.05f),
        ))
        val proposal = result.single()
        assertEquals(95f, proposal.newBaseline, 0.0001f)
    }

    @Test
    fun noOpSuppression_whenTargetIsCloseToBOld() {
        // RIR_2_4 at 80×8 with coef = 0.7 → impliedBaseline = est1RM / 0.7. Align bOld to the grid.
        // raw step is tiny, within cap, rounds back to bOld → no proposal emitted.
        val sets = listOf(
            set(targetWeight = 80f, targetReps = 8, feedback = SetFeedback.RIR_2_4),
        )
        val est1Rm = DefaultProgressionEngine.toOneRepMax(80f, 11)
        val implied = est1Rm / 0.7f
        val rounded = (implied / 2.5f).toInt() * 2.5f
        val result = heuristic.compute(input(
            sets = sets,
            currentCoefficients = mapOf(1L to 0.7f),
            currentBaselines = mapOf(MuscleGroup.CHEST to rounded),
        ))
        assertTrue("expected no proposal, got: $result", result.isEmpty())
    }

    private fun history(
        asOf: Long,
        deltas: List<Float>,  // signed deltas applied to previousBaseline=100; INITIAL skipped
        muscle: MuscleGroup = MuscleGroup.CHEST,
        changeReasons: List<io.github.fowles.stochastic_strength.data.model.BaselineChangeReason> =
            List(deltas.size) { io.github.fowles.stochastic_strength.data.model.BaselineChangeReason.PROGRESSION },
    ): List<BaselineHistory> {
        var prev = 100f
        return deltas.mapIndexed { i, d ->
            val next = prev + d
            val row = BaselineHistory(
                sessionId = (i + 1).toLong(),
                muscleGroup = muscle,
                previousBaseline = prev,
                newBaseline = next,
                changeReason = changeReasons[i],
                timestamp = asOf - (deltas.size - i) * 1000L,
            )
            prev = next
            row
        }
    }

    @Test
    fun safetyOscillation_marksMetadata() {
        // 4-entry history with 2 sign flips. We assert the metadata label is "oscillating".
        val sets = listOf(set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS))
        val now = 10_000_000L
        val recent = history(asOf = now, deltas = listOf(+5f, -5f, +5f, -5f))
        val result = heuristic.compute(input(
            sets = sets,
            recentHistory = mapOf(MuscleGroup.CHEST to recent),
            asOf = now,
        ))
        val proposal = result.single()
        assertTrue(
            "metadata should mark safety=oscillating, was: ${proposal.metadata}",
            proposal.metadata?.contains("safety=oscillating") == true,
        )
    }

    @Test
    fun safetyConsistentUp_doublesUpCap() {
        val sets = listOf(set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS))
        val now = 10_000_000L
        val recent = history(asOf = now, deltas = listOf(+2f, +2f, +2f))
        val result = heuristic.compute(input(
            sets = sets,
            recentHistory = mapOf(MuscleGroup.CHEST to recent),
            currentCoefficients = mapOf(1L to 0.5f),
            asOf = now,
        ))
        val proposal = result.single()
        assertTrue(
            "metadata should mark safety=consistent_up, was: ${proposal.metadata}",
            proposal.metadata?.contains("safety=consistent_up") == true,
        )
        // Doubled cap = 0.0494. raw = 0.3 * 0.4 * ln(234.6/100) ≈ 0.1024 → clamps to 0.0494.
        // B_new = 100 * exp(0.0494) ≈ 105.07 → rounds to 105.
        assertEquals(105f, proposal.newBaseline, 0.0001f)
    }

    @Test
    fun safetyOscillation_doesNotAffectDownCap() {
        // Oscillation with strong down signal: should still get 10% down cap.
        // TOO_HARD with actualReps=1 at 80×8 → est1RM = toOneRepMax(80, 1) = 80.
        // raw = 0.3 * 0.95 * ln(80/100) ≈ -0.0636. downCap (unchanged) = 0.0953 → no bind.
        // B_new = 100 * exp(-0.0636) ≈ 93.84. 93.84 / 2.5 ≈ 37.535 → roundToInt(37.535) = 38 → 95.0.
        val s = set(targetWeight = 80f, targetReps = 8, actualReps = 1, feedback = SetFeedback.TOO_HARD)
        val now = 10_000_000L
        val recent = history(asOf = now, deltas = listOf(+5f, -5f, +5f, -5f))
        val result = heuristic.compute(input(
            sets = listOf(s),
            recentHistory = mapOf(MuscleGroup.CHEST to recent),
            asOf = now,
        ))
        val proposal = result.single()
        assertTrue(
            "metadata should mark safety=oscillating, was: ${proposal.metadata}",
            proposal.metadata?.contains("safety=oscillating") == true,
        )
        assertEquals(95f, proposal.newBaseline, 0.0001f)
    }

    @Test
    fun safetyIgnoresHistoryOlderThanWindow() {
        // 4 alternating-sign entries timestamped 20 days ago — outside the 14-day window.
        val now = 10_000_000L
        val ms = 24L * 60 * 60 * 1000
        val recent = history(asOf = now - 20 * ms, deltas = listOf(+5f, -5f, +5f, -5f))
        val sets = listOf(set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS))
        val result = heuristic.compute(input(
            sets = sets,
            recentHistory = mapOf(MuscleGroup.CHEST to recent),
            asOf = now,
        ))
        val proposal = result.single()
        assertTrue(
            "metadata should mark safety=default, was: ${proposal.metadata}",
            proposal.metadata?.contains("safety=default") == true,
        )
    }

    @Test
    fun downCapBinds_whenRawLogStepExceedsDownCap() {
        // TOO_HARD with actualReps=1 at 40×8 → est1RM = toOneRepMax(40, 1) = 40 (reps=1 short-circuit),
        // confidence = 0.95, isUpperBound = false, isDefinite = true.
        // bOld = 200 (unrealistically high), coef = 1.0 → impliedBaseline = 40 / 1.0 = 40.
        // rawLog = 0.3 * 0.95 * ln(40/200) = 0.285 * ln(0.2) ≈ 0.285 * (-1.6094) ≈ -0.4587.
        // |rawLog| ≈ 0.4587 > downCap = ln(1.10) ≈ 0.0953 → down cap binds → clamped = -0.0953.
        // bRaw = 200 * exp(-ln(1.10)) = 200 / 1.10 ≈ 181.818.
        // bNew = round(181.818, KG) = (181.818 / 2.5).roundToInt() * 2.5 = 73 * 2.5 = 182.5.
        // capBound = true, but bNew (182.5) != bOld (200) → floor does not fire.
        val s = set(targetWeight = 40f, targetReps = 8, actualReps = 1, feedback = SetFeedback.TOO_HARD)
        val result = heuristic.compute(input(
            sets = listOf(s),
            currentBaselines = mapOf(MuscleGroup.CHEST to 200f),
        ))
        val proposal = result.single()
        assertEquals(182.5f, proposal.newBaseline, 0.0001f)
    }

    @Test
    fun multiExercise_confidenceWeightedAggregateArithmetic() {
        // Two exercises in CHEST, each one RIR_2_4 set at *_×5, with *different* coefficients.
        // RIR_2_4 → est1RM = toOneRepMax(weight, targetReps + 3 = 8), confidence = 0.7, non-upper-bound.
        //
        // Exercise 1 (id=1L, coef=1.0): 80×5 → est1RM = toOneRepMax(80, 8) = 104.0
        //   → impliedBaseline_1 = 104.0 / 1.0 = 104.0
        // Exercise 2 (id=2L, coef=0.5): 50×5 → est1RM = toOneRepMax(50, 8) = 67.0
        //   → impliedBaseline_2 = 67.0 / 0.5 = 134.0
        //
        // Both non-upper-bound → both included. Confidence-weighted aggregate:
        //   totalConf  = 0.7 + 0.7 = 1.4
        //   weighted   = (104.0 * 0.7 + 134.0 * 0.7) / 1.4 = (72.8 + 93.8) / 1.4 = 166.6 / 1.4 = 119.0
        //   avgConf    = 1.4 / 2 = 0.7
        //
        // rawLog  = 0.3 * 0.7 * ln(119.0 / 100.0) = 0.21 * ln(1.19) ≈ 0.21 * 0.17395 ≈ 0.03653
        // upCap   = ln(1.025) ≈ 0.02469 → rawLog > upCap → clamped = 0.02469.
        // bRaw    = 100 * exp(0.02469) = 100 * 1.025 = 102.5 → rounds to 102.5.
        val sets = listOf(
            set(exerciseId = 1L, targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            set(exerciseId = 2L, targetWeight = 50f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
        )
        val result = heuristic.compute(input(
            sets = sets,
            currentCoefficients = mapOf(1L to 1.0f, 2L to 0.5f),
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST),
        ))
        assertEquals(1, result.size)
        val proposal = result.single()
        // Verify the aggregate target was 119.0 (encoded in metadata) and the avgConf was 0.7.
        assertTrue(
            "metadata should include target=119.00, was: ${proposal.metadata}",
            proposal.metadata?.contains("target=119.00") == true,
        )
        assertTrue(
            "metadata should include conf=0.70, was: ${proposal.metadata}",
            proposal.metadata?.contains("conf=0.70") == true,
        )
        assertEquals(102.5f, proposal.newBaseline, 0.0001f)
    }

    @Test
    fun safetyIgnoresInitialRowsInWindow() {
        val now = 10_000_000L
        val initial = BaselineHistory(
            sessionId = null,
            muscleGroup = MuscleGroup.CHEST,
            previousBaseline = 0f,
            newBaseline = 100f,
            changeReason = io.github.fowles.stochastic_strength.data.model.BaselineChangeReason.INITIAL,
            timestamp = now - 1000L,
        )
        val progress = history(asOf = now, deltas = listOf(+5f, +5f))
        val sets = listOf(set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS))
        val result = heuristic.compute(input(
            sets = sets,
            recentHistory = mapOf(MuscleGroup.CHEST to listOf(initial) + progress),
            currentCoefficients = mapOf(1L to 0.5f),
            asOf = now,
        ))
        val proposal = result.single()
        // Only 2 PROGRESSION rows in signs → consistentLength = 3 → no doubling. Should be safety=default.
        assertTrue(
            "metadata should mark safety=default, was: ${proposal.metadata}",
            proposal.metadata?.contains("safety=default") == true,
        )
    }
}
