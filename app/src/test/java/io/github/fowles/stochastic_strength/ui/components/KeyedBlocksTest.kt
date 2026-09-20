package io.github.fowles.stochastic_strength.ui.components

import io.github.fowles.stochastic_strength.domain.Block
import io.github.fowles.stochastic_strength.domain.TestRow
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyedBlocksTest {
    private fun id(row: TestRow) = row.tag.first().code.toLong()

    @Test
    fun keyedBlocks_carriesEachBlocksRowsAndSmallestMemberId() {
        val rows = listOf(TestRow("a"), TestRow("c", 2, 7), TestRow("b", 1, 7))
        val keyed = keyedBlocks(rows) { id(it) }

        assertEquals(listOf(Block(0, 1, 3), Block(1, 2, 2)), keyed.map { it.block })
        assertEquals(listOf(rows.subList(0, 1), rows.subList(1, 3)), keyed.map { it.rows })
        assertEquals(listOf(id(rows[0]), id(rows[2])), keyed.map { it.key })
    }

    /**
     * The swipe-away crash: LazyColumn reads a block's key and body after the row list has
     * already shrunk. A [KeyedBlock] answers from what it captured, so a stale block is merely
     * stale — never an index past the end of the live list.
     */
    @Test
    fun keyedBlocks_staleBlockStillReadsWithoutTouchingTheShrunkRowList() {
        val rows = listOf(TestRow("a"), TestRow("b"))
        val stale = keyedBlocks(rows) { id(it) }

        keyedBlocks(rows.subList(0, 1)) { id(it) } // the removal the UI is recomposing for

        val removed = stale[1]
        assertEquals(id(rows[1]), removed.key)
        assertEquals(listOf(rows[1]), removed.rows)
    }
}
