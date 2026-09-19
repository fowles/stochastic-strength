package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.CircuitRow

/**
 * The editing vocabulary for workout structure, shared by the saved-workout editor and the plan
 * preview. Row arguments are row indices; [moveBlock] takes block indices. Every result is
 * normalized. Drags never change membership — only [link] and [unlink] do.
 */
object CircuitEdits {
    private fun <T : CircuitRow<T>> freshId(rows: List<T>): Int =
        (rows.mapNotNull { it.circuitId }.maxOrNull() ?: -1) + 1

    /** Joins row [i] and row [i]+1 (the rows either side of a block boundary) into one circuit. */
    fun <T : CircuitRow<T>> link(rows0: List<T>, i: Int): List<T> {
        if (i < 0 || i + 1 >= rows0.size) return rows0
        val rows = CircuitStructure.normalize(rows0)
        val blocks = CircuitStructure.blocks(rows)
        val upper = blocks.first { i in it.indices }
        val lower = blocks.first { i + 1 in it.indices }
        if (upper == lower) return rows0
        // A solo joining a circuit adopts the circuit's rounds; otherwise the upper block wins.
        val rounds = if (!upper.isCircuit && lower.isCircuit) lower.rounds else upper.rounds
        val id = freshId(rows)
        return CircuitStructure.normalize(rows.mapIndexed { idx, r ->
            if (idx in upper.indices || idx in lower.indices) r.withStructure(rounds, id) else r
        })
    }

    /** Splits a circuit between row [i] and row [i]+1. A side left with one member becomes solo. */
    fun <T : CircuitRow<T>> unlink(rows0: List<T>, i: Int): List<T> {
        if (i < 0 || i + 1 >= rows0.size) return rows0
        val rows = CircuitStructure.normalize(rows0)
        val block = CircuitStructure.blocks(rows).first { i in it.indices }
        if (i + 1 !in block.indices) return rows0
        val id = freshId(rows)
        return CircuitStructure.normalize(rows.mapIndexed { idx, r ->
            if (idx in (i + 1)..block.last) r.withStructure(r.sets, id) else r
        })
    }

    fun <T : CircuitRow<T>> moveBlock(rows0: List<T>, fromBlock: Int, toBlock: Int): List<T> {
        val rows = CircuitStructure.normalize(rows0)
        val blocks = CircuitStructure.blocks(rows).toMutableList()
        if (fromBlock !in blocks.indices || toBlock !in blocks.indices) return rows0
        blocks.add(toBlock, blocks.removeAt(fromBlock))
        return CircuitStructure.normalize(blocks.flatMap { b -> b.indices.map { rows[it] } })
    }

    fun <T : CircuitRow<T>> remove(rows: List<T>, i: Int): List<T> =
        if (i !in rows.indices) rows
        else CircuitStructure.normalize(CircuitStructure.normalize(rows).filterIndexed { idx, _ -> idx != i })

    /** Sets the rounds of row [i]'s block — for a solo row, simply its set count. */
    fun <T : CircuitRow<T>> setRounds(rows0: List<T>, i: Int, n: Int): List<T> {
        if (i !in rows0.indices) return rows0
        val rows = CircuitStructure.normalize(rows0)
        val block = CircuitStructure.blocks(rows).first { i in it.indices }
        val v = n.coerceIn(CircuitStructure.MIN_SETS, CircuitStructure.MAX_SETS)
        return rows.mapIndexed { idx, r -> if (idx in block.indices) r.withStructure(v, r.circuitId) else r }
    }
}
