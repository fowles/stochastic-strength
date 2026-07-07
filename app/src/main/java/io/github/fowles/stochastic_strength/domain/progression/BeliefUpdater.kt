package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.sqrt

/**
 * Pure belief math: aging (variance growth + muscle-keyed detraining drift) and scalar
 * Gaussian / censored (Tobit) observation folds. Folds age the prior first (spec §2).
 */
class BeliefUpdater(private val config: EstimatorConfig = EstimatorConfig()) {

    /** Task 2 fills in q-growth and drift; the stub only re-stamps time so folds compose. */
    fun age(belief: ExerciseBelief, now: Long, muscleLastObs: Long?): ExerciseBelief =
        if (now <= belief.updatedAt) belief else belief.copy(updatedAt = now)

    fun foldGaussian(
        prior: ExerciseBelief,
        obsLnE1rm: Float,
        noiseSd: Float,
        at: Long,
        muscleLastObs: Long?,
    ): ExerciseBelief {
        val aged = age(prior, at, muscleLastObs)
        val s2 = noiseSd * noiseSd
        val k = aged.sigma2 / (aged.sigma2 + s2)
        return ExerciseBelief(
            mu = aged.mu + k * (obsLnE1rm - aged.mu),
            sigma2 = clampVar((1f - k) * aged.sigma2),
            updatedAt = at,
        )
    }

    /**
     * Fold one censored observation z = x + s·ε constrained to [lowerLn, upperLn] (either side
     * may be null = unbounded). Truncated-Gaussian moment match (spec §2) — exact for this model.
     */
    fun foldCensored(
        prior: ExerciseBelief,
        lowerLn: Float?,
        upperLn: Float?,
        noiseSd: Float,
        at: Long,
        muscleLastObs: Long?,
    ): ExerciseBelief {
        val aged = age(prior, at, muscleLastObs)
        val st2 = aged.sigma2 + noiseSd * noiseSd
        val st = sqrt(st2)
        val alpha = (if (lowerLn != null) (lowerLn - aged.mu) / st else -CLAMP).coerceIn(-CLAMP, CLAMP)
        val beta = (if (upperLn != null) (upperLn - aged.mu) / st else CLAMP).coerceIn(-CLAMP, CLAMP)
        val z = NormalCdf.cdf(beta) - NormalCdf.cdf(alpha)
        if (z < MIN_MASS) {
            // Prior mass misses the window entirely: treat as a Gaussian obs at the violated bound.
            val bound = when {
                upperLn != null && aged.mu >= upperLn -> upperLn
                lowerLn != null && aged.mu <= lowerLn -> lowerLn
                else -> lowerLn ?: upperLn ?: aged.mu
            }
            return foldGaussian(aged, bound, noiseSd, at, muscleLastObs)
        }
        val phiA = NormalCdf.pdf(alpha)
        val phiB = NormalCdf.pdf(beta)
        val mz = aged.mu + st * (phiA - phiB) / z
        val vz = st2 * (1f + (alpha * phiA - beta * phiB) / z - ((phiA - phiB) / z).let { it * it })
        val k = aged.sigma2 / st2
        return ExerciseBelief(
            mu = aged.mu + k * (mz - aged.mu),
            sigma2 = clampVar(aged.sigma2 - k * k * (st2 - vz)),
            updatedAt = at,
        )
    }

    internal fun clampVar(v: Float): Float =
        v.coerceIn(config.sigmaMin * config.sigmaMin, config.sigmaMax * config.sigmaMax)

    private companion object {
        const val CLAMP = 6f
        const val MIN_MASS = 1e-6f
    }
}
