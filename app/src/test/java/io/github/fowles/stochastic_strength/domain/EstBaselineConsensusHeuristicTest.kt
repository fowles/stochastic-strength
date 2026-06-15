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
}
