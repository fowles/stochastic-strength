package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.domain.model.PlannedExercise

/**
 * Where the workout stands, row by row, for the rest screen's exercise list. Derived from the same
 * two inputs as [WorkoutSequence]: the plan and the completed working sets per exercise.
 */
object WorkoutProgress {
    enum class Status {
        /** Every set of this exercise is finished. */
        DONE,
        /** A circuit member that has finished the current round and is waiting on the others. */
        DONE_THIS_ROUND,
        UP_NEXT,
        PENDING,
    }

    /** [label] is null on every circuit member but the first: a circuit states its round once. */
    data class Row(val status: Status, val label: String?)

    /**
     * One [Row] per exercise. [currentIndex] is the exercise whose set comes next (-1 for none); it
     * is passed rather than derived so a staged rest can point at its commit target.
     */
    fun rows(exercises: List<PlannedExercise>, done: Map<Long, Int>, currentIndex: Int): List<Row> {
        fun doneOf(i: Int) = done[exercises[i].exercise.id] ?: 0
        fun remaining(i: Int) = exercises[i].sets - doneOf(i)
        return CircuitStructure.blocks(exercises).flatMap { block ->
            fun roundsDone(i: Int) = block.roundsDone(exercises[i].sets, doneOf(i))
            val live = block.indices.filter { remaining(it) > 0 }
            val lead = currentIndex.takeIf { it in block.indices } ?: live.maxByOrNull { remaining(it) }
            val round = lead?.let { roundsDone(it) + 1 }
            val started = currentIndex in block.indices || block.indices.any { doneOf(it) > 0 }
            val unit = if (block.isCircuit) "round" else "set"
            val label = when {
                round == null -> "done"
                !started -> "${block.rounds} $unit${if (block.rounds == 1) "" else "s"}"
                else -> "$unit $round of ${block.rounds}"
            }
            block.indices.map { i ->
                val status = when {
                    remaining(i) <= 0 -> Status.DONE
                    i == currentIndex -> Status.UP_NEXT
                    round != null && roundsDone(i) >= round -> Status.DONE_THIS_ROUND
                    else -> Status.PENDING
                }
                Row(status, label.takeIf { i == block.start })
            }
        }
    }
}
