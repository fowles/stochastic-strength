package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.ln
import kotlin.math.pow

/**
 * Pure session-fold for one exercise's [ExerciseEstimate]. Local: a session for exercise i moves
 * only i's estimate, so a failure never touches siblings (Goal 3 is structural). Cross-informing
 * happens later, at read time, in [MuscleStrengthProjector].
 */
class ExerciseEstimateUpdater(private val config: EstimatorConfig = EstimatorConfig()) {

    /** Confidence decayed from [prior.updatedAt] to [now] by the configured half-life. */
    fun decayedConfidence(prior: ExerciseEstimate, now: Long): Float {
        val age = (now - prior.updatedAt).coerceAtLeast(0L)
        return prior.confidence * 0.5f.pow(age.toFloat() / config.halfLifeMs)
    }

    /**
     * Fold one session's aggregated observation into the estimate. [obsE1rm] is the session's
     * implied 1RM (from SessionSignalExtractor). When the observation is below the current estimate
     * (a failure / low-RIR session) the observation weight is large so the estimate snaps down;
     * [bracketConfidence] (a demonstrated drop-cascade) pushes that weight further toward [wDownSnap].
     */
    fun fold(prior: ExerciseEstimate, obsE1rm: Float, bracketConfidence: Float, now: Long): ExerciseEstimate {
        val c = decayedConfidence(prior, now)
        val obsLn = ln(obsE1rm)
        val isDown = obsLn < prior.lnE
        val s = bracketConfidence.coerceIn(0f, 1f)
        val w = if (!isDown) config.wUp else config.wDown + (config.wDownSnap - config.wDown) * s
        val lnE = (c * prior.lnE + w * obsLn) / (c + w)
        val confidence = (c + w).coerceAtMost(config.confidenceCap)
        return ExerciseEstimate(lnE = lnE, confidence = confidence, updatedAt = now)
    }

    /** HURT: back the estimate off by [hurtFactor]; confidence decays to [now] but is retained. */
    fun hurt(prior: ExerciseEstimate, now: Long): ExerciseEstimate =
        ExerciseEstimate(
            lnE = prior.lnE + ln(config.hurtFactor),
            confidence = decayedConfidence(prior, now),
            updatedAt = now,
        )
}
