package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefFold
import io.github.fowles.stochastic_strength.domain.belief.BeliefPooling
import kotlin.math.exp
import kotlin.math.ln

data class CrossTuningRow(
    val exerciseId: Long,
    val name: String,
    /** (ownE1rm - leaveOneOutPrediction) / leaveOneOutPrediction; signed. 0 when no consensus exists. */
    val agreement: Float,
    /** This exercise's pooling precision as a share of the muscle's total (0..1). */
    val contribution: Float,
)

/**
 * Per-muscle cross-tuning at [now]: how far each exercise's own (aged) belief sits from what its
 * siblings predict (agreement), and how much of the muscle's total pooling precision it carries
 * (contribution = w_i / Σw, w_i = 1/(agedσ_i² + τ²)). Sorted by agreement descending. Pure.
 */
fun computeCrossTuning(
    beliefs: Map<Long, Belief>,
    seedCoef: Map<Long, Float>,
    namesById: Map<Long, String>,
    muscleExerciseIds: List<Long>,
    now: Long,
    config: BeliefConfig = BeliefConfig(),
): List<CrossTuningRow> {
    val fold = BeliefFold(config)
    val pooling = BeliefPooling(config)
    val tau2 = config.tau * config.tau
    val weights = muscleExerciseIds.associateWith { id ->
        val coef = seedCoef[id] ?: return@associateWith 0f
        if (coef <= 0f) return@associateWith 0f
        beliefs[id]?.let { 1f / (fold.aged(it, now).sigma2 + tau2) } ?: 0f
    }
    val totalW = weights.values.sum()

    return muscleExerciseIds.mapNotNull { id ->
        val belief = beliefs[id] ?: return@mapNotNull null
        val coef = seedCoef[id] ?: return@mapNotNull null
        if (coef <= 0f) return@mapNotNull null
        val name = namesById[id] ?: return@mapNotNull null
        val looLevelLn = pooling.effective(beliefs, seedCoef, muscleExerciseIds.filter { it != id }, now).levelLn
        val prediction = looLevelLn?.let { exp(ln(coef) + it) } ?: 0f
        val ownE1rm = exp(fold.aged(belief, now).mu)
        CrossTuningRow(
            exerciseId = id, name = name,
            agreement = if (prediction > 0f) ownE1rm / prediction - 1f else 0f,
            contribution = if (totalW > 0f) (weights[id] ?: 0f) / totalW else 0f,
        )
    }.sortedByDescending { it.agreement }
}
