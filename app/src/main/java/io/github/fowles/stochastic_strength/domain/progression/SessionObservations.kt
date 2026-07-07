package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import kotlin.math.ln

/**
 * The session's implied fresh-1RM for one exercise: a broad-prior fold of the session's set
 * observations. Chart-dot analogue of the deleted aggregateSession — "what did this session's
 * sets say", independent of prior history (prior σ² = 1 ≈ uninformative). Returns null when no
 * set carries a load observation.
 */
fun impliedSessionE1rm(sets: List<WorkoutSet>, config: EstimatorConfig = EstimatorConfig()): Float? {
    val ordered = sets.sortedBy { it.setNumber }
    val anchor = ordered.firstOrNull {
        it.targetWeight > 0f && it.feedback != null && it.feedback != SetFeedback.HURT
    } ?: return null
    val updater = BeliefUpdater(config)
    var belief = ExerciseBelief(
        mu = ln(DefaultProgressionEngine.rawToOneRepMax(anchor.targetWeight, anchor.targetReps)),
        sigma2 = 1f,
        updatedAt = 0L,
    )
    var folded = false
    ordered.forEachIndexed { i, set ->
        val obs = SetObservation.from(set, fatigueRank = i + 1, config = config) ?: return@forEachIndexed
        belief = if (obs.gaussianLn != null) {
            updater.foldGaussian(belief, obs.gaussianLn, obs.noiseSd, at = 0L, muscleLastObs = null)
        } else {
            updater.foldCensored(belief, obs.lowerLn, obs.upperLn, obs.noiseSd, at = 0L, muscleLastObs = null)
        }
        folded = true
    }
    return if (folded) belief.e1rm else null
}
