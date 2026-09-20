package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.CircuitRow

/** A run of rows done round-robin. A solo row is a block of one, so logic never special-cases it. */
data class Block(val start: Int, val size: Int, val rounds: Int) {
    val indices: IntRange get() = start until start + size
    val last: Int get() = start + size - 1
    val isCircuit: Boolean get() = size > 1

    /**
     * Rounds of this block a member with [sets] sets has behind it once [done] of them are logged.
     * A member with fewer sets than the block's rounds (a swap replacement) joined late, so its
     * rounds count from the end: every member then agrees on which round this is.
     */
    fun roundsDone(sets: Int, done: Int): Int = rounds - sets + done
}

object CircuitStructure {
    const val MIN_SETS = 1
    const val MAX_SETS = 10

    /**
     * Contiguous rows sharing a non-null id (from [circuitIdOf]) are one block; `rounds` is the
     * largest member's [roundsOf] (default 1, for callers that don't care about round counts).
     * The adjacency rule lives here so every reader of logged/planned rows shares it.
     */
    fun <T> blocksBy(rows: List<T>, circuitIdOf: (T) -> Int?, roundsOf: (T) -> Int = { 1 }): List<Block> {
        val out = mutableListOf<Block>()
        var start = 0
        while (start < rows.size) {
            val id = circuitIdOf(rows[start])
            var end = start + 1
            if (id != null) while (end < rows.size && circuitIdOf(rows[end]) == id) end++
            out += Block(start, end - start, (start until end).maxOf { roundsOf(rows[it]) })
            start = end
        }
        return out
    }

    /** Contiguous rows sharing a non-null `circuitId` are one block; `rounds` is the largest member `sets`. */
    fun <T : CircuitRow<T>> blocks(rows: List<T>): List<Block> = blocksBy(rows, { it.circuitId }, { it.sets })

    /** Solo rows get a null id; circuits are renumbered 0, 1, 2… in list order. Never touches `sets`. */
    fun <T : CircuitRow<T>> normalize(rows: List<T>): List<T> {
        var nextId = 0
        return blocks(rows).flatMap { b ->
            val id = if (b.isCircuit) nextId++ else null
            b.indices.map { i -> if (rows[i].circuitId == id) rows[i] else rows[i].withStructure(rows[i].sets, id) }
        }
    }

    /** Joins two row lists without letting `tail`'s circuit ids collide with `head`'s. */
    fun <T : CircuitRow<T>> concat(head: List<T>, tail: List<T>): List<T> {
        val shift = (head.mapNotNull { it.circuitId }.maxOrNull() ?: -1) + 1
        return normalize(head + tail.map { r -> r.circuitId?.let { r.withStructure(r.sets, it + shift) } ?: r })
    }

    fun <T : CircuitRow<T>> circuitCount(rows: List<T>): Int = blocks(rows).count { it.isCircuit }
}
