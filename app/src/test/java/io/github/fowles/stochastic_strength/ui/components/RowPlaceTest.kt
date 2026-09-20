package io.github.fowles.stochastic_strength.ui.components

import io.github.fowles.stochastic_strength.domain.Block
import org.junit.Assert.assertEquals
import org.junit.Test

/** [rowPlace] drives which handle/rail [ExerciseRowScaffold] draws for a row in its [Block]. */
class RowPlaceTest {
    @Test
    fun rowPlace_soloBlock_isAlwaysSolo() {
        val solo = Block(start = 2, size = 1, rounds = 1)
        assertEquals(RowPlace.SOLO, rowPlace(solo, 2))
    }

    @Test
    fun rowPlace_circuitBlock_firstRowIsFirst() {
        val circuit = Block(start = 1, size = 3, rounds = 2)
        assertEquals(RowPlace.FIRST, rowPlace(circuit, 1))
    }

    @Test
    fun rowPlace_circuitBlock_lastRowIsLast() {
        val circuit = Block(start = 1, size = 3, rounds = 2)
        assertEquals(RowPlace.LAST, rowPlace(circuit, 3))
    }

    @Test
    fun rowPlace_circuitBlock_interiorRowIsMiddle() {
        val circuit = Block(start = 1, size = 3, rounds = 2)
        assertEquals(RowPlace.MIDDLE, rowPlace(circuit, 2))
    }

    @Test
    fun rowPlace_twoMemberCircuit_hasNoMiddle_onlyFirstAndLast() {
        val circuit = Block(start = 0, size = 2, rounds = 3)
        assertEquals(RowPlace.FIRST, rowPlace(circuit, 0))
        assertEquals(RowPlace.LAST, rowPlace(circuit, 1))
    }
}
