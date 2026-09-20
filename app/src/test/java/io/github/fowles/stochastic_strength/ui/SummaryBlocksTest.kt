package io.github.fowles.stochastic_strength.ui

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryBlocksTest {
    private fun ex(id: Long, setCount: Int, circuitId: Int?) = SummaryExercise(
        name = "E$id", exerciseId = id, circuitId = circuitId,
        sets = (1..setCount).map { SummarySet(it, 20f, 5, 5, SetFeedback.RIR_2_4) },
    )

    @Test
    fun consecutiveExercisesSharingACircuitIdFormOneBlock_roundsIsTheLongestMember() {
        val blocks = summaryBlocks(listOf(ex(1, 2, 0), ex(2, 1, 0), ex(3, 3, null)))
        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), blocks.map { b -> b.exercises.map { it.exerciseId } })
        assertEquals(listOf(true, false), blocks.map { it.isCircuit })
        assertEquals(2, blocks[0].rounds)
    }

    @Test
    fun soloExercisesAreNeverGrouped() {
        assertEquals(2, summaryBlocks(listOf(ex(1, 3, null), ex(2, 3, null))).size)
    }
}
