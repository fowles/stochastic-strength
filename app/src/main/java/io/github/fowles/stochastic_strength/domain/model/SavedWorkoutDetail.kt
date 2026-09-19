package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.CircuitRow
import io.github.fowles.stochastic_strength.data.model.Exercise

/** One row of a saved workout with its exercise resolved. `reps == null` = session decides. */
data class SavedWorkoutEntry(
    val exercise: Exercise,
    val reps: Int?,
    override val sets: Int = PlannedExercise.DEFAULT_SETS,
    override val circuitId: Int? = null,
) : CircuitRow<SavedWorkoutEntry> {
    override fun withStructure(sets: Int, circuitId: Int?) = copy(sets = sets, circuitId = circuitId)
}

data class SavedWorkoutDetail(
    val id: Long,
    /** Empty when the user never named it; show [displayName]. */
    val name: String,
    val entries: List<SavedWorkoutEntry>,
) {
    val displayName: String
        get() = name.ifBlank { SavedWorkoutNaming.defaultName(entries.map { it.exercise.name }) }
}
