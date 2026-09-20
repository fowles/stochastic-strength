package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.domain.model.PlannedExercise

/**
 * The one rule for what comes next in a workout. Position is never stored: it is derived from the
 * plan and how many working sets each exercise has completed, so undo, swap, HURT and end-exercise
 * are just edits to those two inputs.
 */
object WorkoutSequence {
    data class Step(val exerciseIndex: Int, val setIndex: Int)

    /**
     * The first block with work left; within it, the member with the most sets remaining (earliest
     * on ties). For an authored circuit that is round-robin in slot order; it also keeps slot order
     * once a swap or an early end has left members uneven.
     */
    fun next(exercises: List<PlannedExercise>, done: Map<Long, Int>): Step? {
        fun remaining(i: Int) = exercises[i].sets - (done[exercises[i].exercise.id] ?: 0)
        for (block in CircuitStructure.blocks(exercises)) {
            val pick = block.indices.filter { remaining(it) > 0 }.maxByOrNull { remaining(it) } ?: continue
            return Step(pick, done[exercises[pick].exercise.id] ?: 0)
        }
        return null
    }

    /** "Set 2 of 4" for a solo row, "Round 2 of 2" for a circuit member. Display copy only. */
    fun positionLabel(exercises: List<PlannedExercise>, exerciseIndex: Int, setIndex: Int): String {
        val block = CircuitStructure.blocks(exercises).first { exerciseIndex in it.indices }
        val sets = exercises[exerciseIndex].sets
        return if (block.isCircuit) "Round ${block.roundsDone(sets, setIndex) + 1} of ${block.rounds}"
        else "Set ${setIndex + 1} of $sets"
    }

    /**
     * "Round 2 of 2" when [step] lands on a circuit member, null for a solo exercise. The single
     * circuit-vs-solo distinction consumers (rest screen, notification) key their "up next" round
     * copy off of, so it is derived once here rather than re-checking `circuitId` at each call site.
     */
    fun circuitRoundLabel(exercises: List<PlannedExercise>, step: Step): String? {
        val block = CircuitStructure.blocks(exercises).first { step.exerciseIndex in it.indices }
        return if (block.isCircuit) positionLabel(exercises, step.exerciseIndex, step.setIndex) else null
    }

    /**
     * True when [step] — the workout's next real step, from [next] — is a set for [exerciseIndex].
     * Always true for a solo exercise's own continuation; false whenever the next real set belongs
     * to a different exercise, which in a circuit is the common case. Consumers use this to gate
     * exercise-scoped "what's next" copy (e.g. a reduced-weight notice) that would otherwise
     * describe the wrong exercise's upcoming set.
     */
    fun isNextStepFor(step: Step?, exerciseIndex: Int): Boolean = step?.exerciseIndex == exerciseIndex
}
