package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
import kotlin.math.exp

data class CrossTuningRow(
    val exerciseId: Long,
    val name: String,
    /** (ownE1rm - leaveOneOutPrediction) / leaveOneOutPrediction; signed. 0 when no consensus exists. */
    val agreement: Float,
    /** This exercise's pooling precision as a share of the muscle's total (0..1). */
    val contribution: Float,
)

/**
 * Per-muscle cross-tuning at [now]: how far each exercise's own belief sits from what its siblings
 * predict (agreement), and how much of the muscle's total pooling precision it carries (contribution).
 * Sorted by agreement descending. Pure.
 */
fun computeCrossTuning(
    beliefs: Map<Long, ExerciseBelief>,
    seedCoef: Map<Long, Float>,
    namesById: Map<Long, String>,
    muscleExerciseIds: List<Long>,
    now: Long,
    muscleLastObs: Long? = null,
    config: EstimatorConfig = EstimatorConfig(),
    projector: MuscleStrengthProjector = MuscleStrengthProjector(config),
    equipment: Map<Long, Equipment> = emptyMap(),
): List<CrossTuningRow> {
    val updater = BeliefUpdater(config)
    val precById = muscleExerciseIds.associateWith { id ->
        val b = beliefs[id] ?: return@associateWith 0f
        projector.poolPrecision(updater.age(b, now, muscleLastObs), config.tauFor(equipment[id]))
    }
    val totalPrec = precById.values.sum()

    val rows = muscleExerciseIds.mapNotNull { id ->
        val belief = beliefs[id] ?: return@mapNotNull null
        val seed = seedCoef[id] ?: return@mapNotNull null
        if (seed <= 0f) return@mapNotNull null
        val name = namesById[id] ?: return@mapNotNull null

        val aged = updater.age(belief, now, muscleLastObs)
        val leaveOneOut =
            projector.project(beliefs, seedCoef, muscleExerciseIds.filter { it != id }, now, muscleLastObs, equipment)
        val prediction = leaveOneOut.level * seed
        val ownE1rm = exp(aged.mu)
        val agreement = if (prediction > 0f) ownE1rm / prediction - 1f else 0f
        val contribution = if (totalPrec > 0f) precById.getValue(id) / totalPrec else 0f

        CrossTuningRow(exerciseId = id, name = name, agreement = agreement, contribution = contribution)
    }
    return rows.sortedByDescending { it.agreement }
}
