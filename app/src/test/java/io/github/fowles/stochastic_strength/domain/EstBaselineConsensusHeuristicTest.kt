package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
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
