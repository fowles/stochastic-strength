package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.CircuitRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** Minimal [CircuitRow] for structure tests; [tag] identifies the row across edits. */
data class TestRow(val tag: String, override val sets: Int = 3, override val circuitId: Int? = null) :
    CircuitRow<TestRow> {
    override fun withStructure(sets: Int, circuitId: Int?) = copy(sets = sets, circuitId = circuitId)
}

class CircuitStructureTest {
    private fun ids(rows: List<TestRow>) = rows.map { it.circuitId }

    @Test
    fun blocks_soloRowsAreBlocksOfOne() {
        val blocks = CircuitStructure.blocks(listOf(TestRow("a", sets = 4), TestRow("b")))
        assertEquals(listOf(Block(0, 1, 4), Block(1, 1, 3)), blocks)
    }

    @Test
    fun blocks_contiguousSharedIdIsOneCircuit_roundsIsMaxSets() {
        val rows = listOf(TestRow("a"), TestRow("b", 2, 7), TestRow("c", 1, 7), TestRow("d"))
        assertEquals(listOf(Block(0, 1, 3), Block(1, 2, 2), Block(3, 1, 3)), CircuitStructure.blocks(rows))
    }

    @Test
    fun normalize_tagReappearingAfterGapStartsNewCircuit_andSinglesCollapse() {
        val rows = listOf(TestRow("a", 3, 0), TestRow("b"), TestRow("c", 3, 0))
        assertEquals(listOf(null, null, null), ids(CircuitStructure.normalize(rows)))
    }

    @Test
    fun normalize_renumbersDenselyInListOrder() {
        val rows = listOf(
            TestRow("a", 3, 9), TestRow("b", 3, 9), TestRow("c"),
            TestRow("d", 3, 4), TestRow("e", 3, 4),
        )
        assertEquals(listOf(0, 0, null, 1, 1), ids(CircuitStructure.normalize(rows)))
    }

    @Test
    fun normalize_doesNotEqualizeSets() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 1, 0))
        assertEquals(listOf(2, 1), CircuitStructure.normalize(rows).map { it.sets })
    }

    @Test
    fun concat_shiftsTailIdsSoAdjacentCircuitsStayDistinct() {
        val head = listOf(TestRow("a", 3, 0), TestRow("b", 3, 0))
        val tail = listOf(TestRow("c", 2, 0), TestRow("d", 2, 0))
        assertEquals(listOf(0, 0, 1, 1), ids(CircuitStructure.concat(head, tail)))
    }

    @Test
    fun circuitCount_countsOnlyMultiMemberBlocks() {
        val rows = listOf(TestRow("a", 3, 0), TestRow("b", 3, 0), TestRow("c"))
        assertEquals(1, CircuitStructure.circuitCount(rows))
    }
}
