package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefPooling
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
 * Per-muscle cross-tuning at [now]: how far each exercise's own (aged) belief sits from what its
 * siblings predict (agreement), and how much of the muscle's total pooling precision it carries
 * (contribution). Both read straight off [BeliefPooling.effective]'s breakdown — the numbers shown
 * are the pooling that actually runs. Sorted by agreement descending. Pure.
 */
fun computeCrossTuning(
    beliefs: Map<Long, Belief>,
    seedCoef: Map<Long, Float>,
    namesById: Map<Long, String>,
    muscleExerciseIds: List<Long>,
    now: Long,
    config: BeliefConfig = BeliefConfig(),
): List<CrossTuningRow> {
    val pool = BeliefPooling(config).effective(beliefs, seedCoef, muscleExerciseIds, now)
    return muscleExerciseIds.mapNotNull { id ->
        val eff = pool.effective[id] ?: return@mapNotNull null
        val own = eff.own ?: return@mapNotNull null
        val name = namesById[id] ?: return@mapNotNull null
        val prediction = eff.sibling?.let { exp(it.mu) } ?: 0f
        CrossTuningRow(
            exerciseId = id, name = name,
            agreement = if (prediction > 0f) exp(own.mu) / prediction - 1f else 0f,
            contribution = if (pool.totalVoterWeight > 0f) eff.voterWeight / pool.totalVoterWeight else 0f,
        )
    }.sortedByDescending { it.agreement }
}
