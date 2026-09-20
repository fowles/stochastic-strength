package io.github.fowles.stochastic_strength.data.model

/**
 * A row that takes part in workout structure: `sets` is its own set count, and rows that are
 * adjacent and share a non-null `circuitId` form one circuit. Members may be uneven; the circuit's
 * rounds are the largest member's `sets` (`CircuitStructure.blocks`).
 */
interface CircuitRow<T> {
    val sets: Int
    val circuitId: Int?
    fun withStructure(sets: Int, circuitId: Int?): T
}
