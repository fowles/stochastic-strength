package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
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
    ) = BaselineComputationInput(
        sets = sets,
        exerciseMuscle = exerciseMuscle,
        currentCoefficients = currentCoefficients,
        currentBaselines = currentBaselines,
        recentHistory = recentHistory,
        sessionReps = sessionReps,
        minReductionFractions = minReductionFractions,
        asOf = asOf,
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
}
