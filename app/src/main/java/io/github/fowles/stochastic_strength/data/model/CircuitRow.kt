package io.github.fowles.stochastic_strength.data.model

/**
 * A row that takes part in workout structure: `sets` is its set count (for a circuit member, the
 * circuit's rounds); rows that are adjacent and share a non-null `circuitId` form one circuit.
 */
interface CircuitRow<T> {
    val sets: Int
    val circuitId: Int?
    fun withStructure(sets: Int, circuitId: Int?): T
}
