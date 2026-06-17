package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun reducedExercise_contributesNoUpSignal() {
        // Set 1 at full 100 failed, sets 2-3 dropped to 90 and hit target with reserve.
        val sets = listOf(
            set(setNumber = 1, targetWeight = 100f, targetReps = 10, actualReps = 7, feedback = SetFeedback.TOO_HARD),
            set(setNumber = 2, targetWeight = 90f, feedback = SetFeedback.RIR_2_4),
            set(setNumber = 3, targetWeight = 90f, feedback = SetFeedback.RIR_0_1),
        )
        assertNull(heuristic.exerciseTargetPct(sets))
    }
}
