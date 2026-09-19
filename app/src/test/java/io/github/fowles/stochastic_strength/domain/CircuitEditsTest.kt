package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CircuitEditsTest {
    private fun shape(rows: List<TestRow>) = rows.map { Triple(it.tag, it.sets, it.circuitId) }

    @Test
    fun link_twoSolos_makesCircuitWithUpperRowsSets() {
        val out = CircuitEdits.link(listOf(TestRow("a", 4), TestRow("b", 2), TestRow("c")), 0)
        assertEquals(listOf(Triple("a", 4, 0), Triple("b", 4, 0), Triple("c", 3, null)), shape(out))
    }

    @Test
    fun link_soloAboveCircuit_joinsAndAdoptsCircuitRounds() {
        val rows = listOf(TestRow("a", 5), TestRow("b", 2, 0), TestRow("c", 2, 0))
        assertEquals(listOf(2, 2, 2), CircuitEdits.link(rows, 0).map { it.sets })
        assertEquals(listOf(0, 0, 0), CircuitEdits.link(rows, 0).map { it.circuitId })
    }

    @Test
    fun link_circuitAboveSolo_joinsAndAdoptsCircuitRounds() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 2, 0), TestRow("c", 5))
        assertEquals(listOf(2, 2, 2), CircuitEdits.link(rows, 1).map { it.sets })
    }

    @Test
    fun link_twoCircuits_mergeWithUpperRounds() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 2, 0), TestRow("c", 4, 1), TestRow("d", 4, 1))
        val out = CircuitEdits.link(rows, 1)
        assertEquals(listOf(0, 0, 0, 0), out.map { it.circuitId })
        assertEquals(listOf(2, 2, 2, 2), out.map { it.sets })
    }

    @Test
    fun link_insideOneBlock_orOutOfRange_isNoOp() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 2, 0))
        assertEquals(rows, CircuitEdits.link(rows, 0))
        assertEquals(rows, CircuitEdits.link(rows, 1))
    }

    @Test
    fun unlink_splitsAndSinglesBecomeSoloKeepingRounds() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 2, 0), TestRow("c", 2, 0))
        val out = CircuitEdits.unlink(rows, 0)
        assertEquals(listOf(Triple("a", 2, null), Triple("b", 2, 0), Triple("c", 2, 0)), shape(out))
    }

    @Test
    fun unlink_betweenBlocks_isNoOp() {
        val rows = listOf(TestRow("a"), TestRow("b"))
        assertEquals(rows, CircuitEdits.unlink(rows, 0))
    }

    @Test
    fun moveBlock_movesWholeCircuit_andNeverChangesMembership() {
        val rows = listOf(TestRow("a"), TestRow("b", 2, 0), TestRow("c", 2, 0), TestRow("d"))
        val out = CircuitEdits.moveBlock(rows, fromBlock = 1, toBlock = 0)
        assertEquals(listOf("b", "c", "a", "d"), out.map { it.tag })
        assertEquals(listOf(0, 0, null, null), out.map { it.circuitId })
    }

    @Test
    fun moveBlock_staleNonContiguousTagsDoNotMergeWhenMadeAdjacent() {
        // "a" and "c" carry the same stale id but are not a circuit; moving "b" away must not fuse them.
        val rows = listOf(TestRow("a", 3, 0), TestRow("b"), TestRow("c", 3, 0))
        val out = CircuitEdits.moveBlock(rows, fromBlock = 1, toBlock = 2)
        assertEquals(listOf(null, null, null), out.map { it.circuitId })
    }

    @Test
    fun remove_downToOneMember_dissolvesTheCircuit() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 2, 0))
        assertEquals(listOf(Triple("b", 2, null)), shape(CircuitEdits.remove(rows, 0)))
    }

    @Test
    fun setRounds_writesEveryMember_andClampsToRange() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 2, 0), TestRow("c"))
        assertEquals(listOf(10, 10, 3), CircuitEdits.setRounds(rows, 1, 99).map { it.sets })
        assertEquals(listOf(2, 2, 1), CircuitEdits.setRounds(rows, 2, 0).map { it.sets })
    }
}
