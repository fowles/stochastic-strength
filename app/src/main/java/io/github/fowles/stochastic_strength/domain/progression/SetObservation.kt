package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One working set translated into an observation of ln(fresh 1RM) (spec §2). Exactly one of
 * [gaussianLn] (counted failure) or the censored window [lowerLn, upperLn] is populated; a null
 * bound is unbounded on that side. Values are on the FRESH basis: set k under fatigue observes
 * capacity·(1 − φ(k−1)), so bounds are shifted by −ln(1 − φ(k−1)).
 */
data class SetObservation(
    val lowerLn: Float?,
    val upperLn: Float?,
    val gaussianLn: Float?,
    val noiseSd: Float,
) {
    companion object {
        /**
         * [fatigueRank] = the set's 1-based rank by setNumber among ALL of its exercise's rows in
         * the session (feedback-less/HURT rows still occupy a rank — the attempt fatigued the
         * lifter). Returns null when the set carries no load observation.
         */
        fun from(set: WorkoutSet, fatigueRank: Int, config: EstimatorConfig = EstimatorConfig()): SetObservation? {
            val feedback = set.feedback ?: return null
            if (feedback == SetFeedback.HURT) return null
            val w = set.targetWeight
            if (w <= 0f) return null
            val r = set.targetReps
            val freshShift = -ln(1f - (config.fatiguePerSet * (fatigueRank - 1)).coerceAtMost(0.5f))
            fun capLn(reps: Float) = ln(DefaultProgressionEngine.rawToOneRepMax(w, reps)) + freshShift
            val lambda = repSlope(w, r)
            fun noise(base: Float): Float {
                val repSd = lambda * sqrt(base * base + (config.repNoiseRel * r) * (config.repNoiseRel * r))
                return sqrt(repSd * repSd + config.obsModelSd * config.obsModelSd)
            }
            return when (feedback) {
                SetFeedback.TOO_HARD -> {
                    val a = set.actualReps
                    if (a != null) SetObservation(null, null, capLn(a + 0.5f), noise(config.repNoiseCounted))
                    else SetObservation(null, capLn(r.toFloat()), null, noise(config.repNoiseBucket))
                }
                SetFeedback.RIR_0_1 -> SetObservation(capLn(r.toFloat()), capLn(r + 2f), null, noise(config.repNoiseBucket))
                SetFeedback.RIR_2_4 -> SetObservation(capLn(r + 2f), capLn(r + 5f), null, noise(config.repNoiseBucket))
                SetFeedback.RIR_5_PLUS -> SetObservation(capLn(r + 5f), null, null, noise(config.repNoiseBucket))
                SetFeedback.HURT -> null
            }
        }

        /** Local slope λ = ∂ln f(w, ρ)/∂ρ at ρ = r, central difference (spec §2). */
        fun repSlope(weight: Float, reps: Int): Float {
            val lo = (reps - 0.5f).coerceAtLeast(1f)
            val hi = reps + 0.5f
            val slope = (ln(DefaultProgressionEngine.rawToOneRepMax(weight, hi)) -
                ln(DefaultProgressionEngine.rawToOneRepMax(weight, lo))) / (hi - lo)
            return slope.coerceAtLeast(1e-4f)
        }
    }
}
