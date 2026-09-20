package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.WorkoutProgress.Status.DONE
import io.github.fowles.stochastic_strength.domain.WorkoutProgress.Status.DONE_THIS_ROUND
import io.github.fowles.stochastic_strength.domain.WorkoutProgress.Status.PENDING
import io.github.fowles.stochastic_strength.domain.WorkoutProgress.Status.UP_NEXT
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutProgressTest {
    private fun pe(id: Long, sets: Int = 3, circuitId: Int? = null) = PlannedExercise(
        exercise = Exercise(id = id, name = "E$id", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.DUMBBELL),
        sets = sets, circuitId = circuitId,
    )

    /** Rows as they stand when the next set is whatever [WorkoutSequence.next] says. */
    private fun rows(exercises: List<PlannedExercise>, done: Map<Long, Int>) =
        WorkoutProgress.rows(exercises, done, WorkoutSequence.next(exercises, done)?.exerciseIndex ?: -1)

    @Test
    fun soloRows_countTheUpcomingSet() {
        val plan = listOf(pe(1, sets = 2), pe(2, sets = 4), pe(3, sets = 1))
        val r = rows(plan, mapOf(1L to 2, 2L to 2))
        assertEquals(listOf(DONE, UP_NEXT, PENDING), r.map { it.status })
        assertEquals(listOf("done", "set 3 of 4", "1 set"), r.map { it.label })
    }

    @Test
    fun soloRow_aboutToStart_isSetOne() {
        val r = rows(listOf(pe(1, sets = 4)), emptyMap())
        assertEquals("set 1 of 4", r.single().label)
    }

    @Test
    fun circuit_labelsTheRoundOnce_andMarksMembersWithinIt() {
        val plan = listOf(pe(1, 3, 0), pe(2, 3, 0), pe(3, 3, 0), pe(4))
        // Round 1 complete, curl done for round 2, kickback next.
        val r = rows(plan, mapOf(1L to 2, 2L to 1, 3L to 1))
        assertEquals(listOf(DONE_THIS_ROUND, UP_NEXT, PENDING, PENDING), r.map { it.status })
        assertEquals(listOf("round 2 of 3", null, null, "3 sets"), r.map { it.label })
    }

    @Test
    fun circuit_newRound_resetsMembersToPending() {
        val plan = listOf(pe(1, 2, 0), pe(2, 2, 0))
        val r = rows(plan, mapOf(1L to 1, 2L to 1))
        assertEquals(listOf(UP_NEXT, PENDING), r.map { it.status })
        assertEquals("round 2 of 2", r[0].label)
    }

    @Test
    fun circuit_notStarted_andFinished() {
        val plan = listOf(pe(9, sets = 1), pe(1, 2, 0), pe(2, 2, 0))
        assertEquals(listOf("set 1 of 1", "2 rounds", null), rows(plan, emptyMap()).map { it.label })
        val finished = rows(plan, mapOf(9L to 1, 1L to 2, 2L to 2))
        assertEquals(listOf(DONE, DONE, DONE), finished.map { it.status })
        assertEquals(listOf("done", "done", null), finished.map { it.label })
    }

    @Test
    fun circuit_memberEndedEarly_isDone_andTheRoundFollowsTheOthers() {
        val plan = listOf(pe(1, 3, 0), pe(2, 3, 0), pe(3, 3, 0))
        // HURT on E2 during round 1 marks all of its sets done.
        val r = rows(plan, mapOf(1L to 1, 2L to 3))
        assertEquals(listOf(DONE_THIS_ROUND, DONE, UP_NEXT), r.map { it.status })
        assertEquals("round 1 of 3", r[0].label)
    }

    @Test
    fun circuit_afterASwap_replacementCarriesTheRemainingRounds() {
        // E2 was swapped after round 1: it counts as done, and E5 has the 2 rounds that were left.
        val plan = listOf(pe(1, 3, 0), pe(2, 3, 0), pe(5, 2, 0))
        val r = rows(plan, mapOf(1L to 2, 2L to 3))
        assertEquals(listOf(DONE_THIS_ROUND, DONE, UP_NEXT), r.map { it.status })
        assertEquals("round 2 of 3", r[0].label)
    }

    @Test
    fun currentIndex_overridesTheDerivedStep_forAStagedRest() {
        val plan = listOf(pe(1, sets = 2), pe(2, sets = 2))
        val r = WorkoutProgress.rows(plan, mapOf(1L to 1), currentIndex = 1)
        assertEquals(listOf(PENDING, UP_NEXT), r.map { it.status })
        assertEquals(listOf("set 2 of 2", "set 1 of 2"), r.map { it.label })
    }
}
