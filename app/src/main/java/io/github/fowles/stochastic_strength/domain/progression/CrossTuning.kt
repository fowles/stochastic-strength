package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.exp

data class CrossTuningRow(
    val exerciseId: Long,
    val name: String,
    /** (ownE1rm - leaveOneOutPrediction) / leaveOneOutPrediction; signed. 0 when no consensus exists. */
    val agreement: Float,
    /** This exercise's decayed confidence as a share of the muscle's total (0..1). */
    val contribution: Float,
)

/**
 * Per-muscle cross-tuning at [now]: how far each exercise's own estimate sits from what its siblings
 * predict (agreement), and how much of the muscle's total decayed confidence it carries (contribution).
 * Sorted by agreement descending. Pure.
 */
fun computeCrossTuning(
    estimates: Map<Long, ExerciseEstimate>,
    seedCoef: Map<Long, Float>,
    namesById: Map<Long, String>,
    muscleExerciseIds: List<Long>,
    now: Long,
    projector: MuscleStrengthProjector = MuscleStrengthProjector(),
    updater: ExerciseEstimateUpdater = ExerciseEstimateUpdater(),
): List<CrossTuningRow> {
    val confById = muscleExerciseIds.associateWith { id ->
        estimates[id]?.let { updater.decayedConfidence(it, now) } ?: 0f
    }
    val totalConf = confById.values.sum()

    val rows = muscleExerciseIds.mapNotNull { id ->
        val estimate = estimates[id] ?: return@mapNotNull null
        val seed = seedCoef[id] ?: return@mapNotNull null
        if (seed <= 0f) return@mapNotNull null
        val name = namesById[id] ?: return@mapNotNull null

        val leaveOneOut = projector.project(estimates, seedCoef, muscleExerciseIds.filter { it != id }, now)
        val prediction = leaveOneOut.level * seed
        val ownE1rm = exp(estimate.lnE)
        val agreement = if (prediction > 0f) ownE1rm / prediction - 1f else 0f
        val contribution = if (totalConf > 0f) (confById[id] ?: 0f) / totalConf else 0f

        CrossTuningRow(exerciseId = id, name = name, agreement = agreement, contribution = contribution)
    }
    return rows.sortedByDescending { it.agreement }
}
