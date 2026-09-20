package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.WorkoutSequence.Step
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutSequenceTest {
    private fun pe(id: Long, sets: Int = 3, circuitId: Int? = null) = PlannedExercise(
        exercise = Exercise(id = id, name = "E$id", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.DUMBBELL),
        sets = sets, circuitId = circuitId,
    )

    /** Walks the sequence to the end, returning exercise ids in performance order. */
    private fun walk(exercises: List<PlannedExercise>, start: Map<Long, Int> = emptyMap()): List<Long> {
        val done = start.toMutableMap()
        val order = mutableListOf<Long>()
        while (true) {
            val step = WorkoutSequence.next(exercises, done) ?: return order
            val id = exercises[step.exerciseIndex].exercise.id
            assertEquals("setIndex must equal done count", done[id] ?: 0, step.setIndex)
            order += id
            done[id] = step.setIndex + 1
        }
    }

    @Test
    fun soloRows_runEachExercisesSetsContiguously() {
        assertEquals(listOf(1L, 1L, 2L, 2L, 2L), walk(listOf(pe(1, sets = 2), pe(2, sets = 3))))
    }

    @Test
    fun circuit_interleavesMembersInSlotOrderPerRound() {
        val plan = listOf(pe(1, 2, 0), pe(2, 2, 0), pe(3, 2, 0))
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L, 3L), walk(plan))
    }

    @Test
    fun mixedBlocks_finishOneBlockBeforeTheNext() {
        val plan = listOf(pe(1, 1), pe(2, 2, 0), pe(3, 2, 0), pe(4, 1))
        assertEquals(listOf(1L, 2L, 3L, 2L, 3L, 4L), walk(plan))
    }

    @Test
    fun memberEndedEarly_dropsOutOfLaterRounds() {
        // HURT / end-exercise marks a member done: done == sets.
        val plan = listOf(pe(1, 3, 0), pe(2, 3, 0))
        assertEquals(listOf(1L, 1L), walk(plan, start = mapOf(1L to 1, 2L to 3)))
    }

    @Test
    fun swappedInMemberWithFewerSets_keepsItsSlotOrder() {
        // Round 1 done by 1, 9(original), 3. 9 was swapped for 2, which owes the remaining 2 of 3 rounds.
        val plan = listOf(pe(1, 3, 0), pe(9, 3, 0), pe(2, 2, 0), pe(3, 3, 0))
        val start = mapOf(1L to 1, 9L to 3, 3L to 1)
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L, 3L), walk(plan, start))
    }

    @Test
    fun next_isNullWhenEverythingIsDone() {
        assertNull(WorkoutSequence.next(listOf(pe(1, 1)), mapOf(1L to 1)))
        assertNull(WorkoutSequence.next(emptyList(), emptyMap()))
    }

    @Test
    fun next_reportsIndexAndSetIndex() {
        val plan = listOf(pe(1, 2, 0), pe(2, 2, 0))
        assertEquals(Step(exerciseIndex = 1, setIndex = 0), WorkoutSequence.next(plan, mapOf(1L to 1)))
    }

    @Test
    fun positionLabel_soloSaysSet_circuitSaysRound() {
        val plan = listOf(pe(1, 4), pe(2, 2, 0), pe(3, 2, 0))
        assertEquals("Set 2 of 4", WorkoutSequence.positionLabel(plan, 0, 1))
        assertEquals("Round 2 of 2", WorkoutSequence.positionLabel(plan, 2, 1))
    }

    @Test
    fun positionLabel_swappedInMemberCountsRoundsFromTheCircuitsTotal() {
        val plan = listOf(pe(1, 3, 0), pe(2, 2, 0)) // 2 joined at round 2 of 3
        assertEquals("Round 2 of 3", WorkoutSequence.positionLabel(plan, 1, 0))
    }

    @Test
    fun circuitRoundLabel_nullForSoloStep() {
        val plan = listOf(pe(1, 4))
        assertNull(WorkoutSequence.circuitRoundLabel(plan, Step(exerciseIndex = 0, setIndex = 1)))
    }

    @Test
    fun circuitRoundLabel_roundTextForCircuitMemberStep() {
        val plan = listOf(pe(1, 4), pe(2, 2, 0), pe(3, 2, 0))
        assertEquals(
            "Round 2 of 2",
            WorkoutSequence.circuitRoundLabel(plan, Step(exerciseIndex = 2, setIndex = 1)),
        )
    }

    @Test
    fun circuitRoundLabel_mixedBlocks_soloAndCircuitStepsInSamePlan() {
        val plan = listOf(pe(1, 1), pe(2, 2, 0), pe(3, 2, 0), pe(4, 1))
        assertNull(WorkoutSequence.circuitRoundLabel(plan, Step(exerciseIndex = 0, setIndex = 0)))
        assertEquals(
            "Round 1 of 2",
            WorkoutSequence.circuitRoundLabel(plan, Step(exerciseIndex = 1, setIndex = 0)),
        )
        assertNull(WorkoutSequence.circuitRoundLabel(plan, Step(exerciseIndex = 3, setIndex = 0)))
    }
}
