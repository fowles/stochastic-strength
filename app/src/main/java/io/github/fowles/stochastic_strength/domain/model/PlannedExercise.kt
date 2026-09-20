package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.CircuitRow
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise

data class PlannedExercise(
    val exercise: Exercise,
    val sessionWeight: Float = 0f,
    val sessionReps: Int = 10,
    val warmupSets: List<WarmupSet> = emptyList(),
    val estimatedSeconds: Int = 0,
    override val sets: Int = DEFAULT_SETS,
    override val circuitId: Int? = null,
    /**
     * A pinned value is the user's own number: the rep slider skips pinned reps and nothing
     * reprices a pinned weight.
     */
    val repsPinned: Boolean = false,
    val weightPinned: Boolean = false,
) : CircuitRow<PlannedExercise> {
    override fun withStructure(sets: Int, circuitId: Int?) = copy(sets = sets, circuitId = circuitId)

    /**
     * Whether this exercise puts an actual load on the user. False for bodyweight and timed work,
     * which has no weight to add to, reduce, or call heavy.
     */
    val isWeighted: Boolean get() =
        !exercise.isTimed && exercise.equipment != Equipment.BODYWEIGHT && sessionWeight > 0f

    companion object {
        /** Set count for rows the app adds on its own (generated, added, restocked). */
        const val DEFAULT_SETS = 3
    }
}
