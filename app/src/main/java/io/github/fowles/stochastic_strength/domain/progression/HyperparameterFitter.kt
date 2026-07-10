package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import kotlin.math.exp

/** Guardrail constants for per-user fitting (spec §8). Multipliers are on each parameter's default. */
data class FitConfig(
    val minFitSessions: Int = 15,
    val boundMultiplierLo: Double = 0.25,
    val boundMultiplierHi: Double = 4.0,
    val priorSd: Double = 0.5,
    val maxIterations: Int = 200,
)

/**
 * Per-user MAP fitting (spec §2–§3). Nelder-Mead over four log-multipliers on [defaults]
 * (order: drift, fatigue, procNoise, tau). Each objective evaluation is one in-memory
 * scored replay (predictive log-likelihood via [PredictiveScoreAccumulator]) plus lognormal
 * log-priors centered on the defaults. MAP; regularized so a thin history stays at defaults.
 * θ is never persisted; the caller caches the returned config.
 *
 * Feedback-trust (repNoise) is deliberately NOT fitted: on real history the rep-noise multiplier
 * saturated at its ×4 cap, i.e. the fit learned to distrust the user's own feedback — a failure
 * mode. It is pinned at its default and left out of the fitted set.
 */
class HyperparameterFitter(
    private val defaults: EstimatorConfig = EstimatorConfig(),
    private val fitConfig: FitConfig = FitConfig(),
) {
    data class Result(
        val config: EstimatorConfig,
        val score: Double,
        val defaultScore: Double,
        val atDefaults: Boolean,
        val sessionCount: Int,
    )

    /** Maps four log-multipliers onto the defaults, each multiplier clamped to [lo, hi]. */
    fun applyTheta(logTheta: DoubleArray): EstimatorConfig {
        fun m(i: Int): Float =
            exp(logTheta[i]).coerceIn(fitConfig.boundMultiplierLo, fitConfig.boundMultiplierHi).toFloat()
        return defaults.copy(
            detrainRatePerWeek = defaults.detrainRatePerWeek * m(0),
            fatiguePerSet = defaults.fatiguePerSet * m(1),
            processNoisePerDay = defaults.processNoisePerDay * m(2),
            tauBarbell = defaults.tauBarbell * m(3),
            tauMachineCable = defaults.tauMachineCable * m(3),
            tauOtherLoaded = defaults.tauOtherLoaded * m(3),
        )
    }

    fun fit(history: ReplayHistory, newSnapshot: () -> ReplaySnapshot): Result {
        val sessionCount = history.sessions.count { it.endTime != null }
        val defaultScore = mapObjective(DoubleArray(4) { 0.0 }, history, newSnapshot)
        if (sessionCount < fitConfig.minFitSessions) {
            return Result(defaults, defaultScore, defaultScore, atDefaults = true, sessionCount)
        }
        // NelderMead minimizes, so it optimizes the negated MAP objective.
        val best = NelderMead.minimize(DoubleArray(4) { 0.0 }, step = 0.35, maxIter = fitConfig.maxIterations) {
            -mapObjective(it, history, newSnapshot)
        }
        val bestScore = mapObjective(best, history, newSnapshot)
        return if (bestScore > defaultScore) {
            Result(applyTheta(best), bestScore, defaultScore, atDefaults = false, sessionCount)
        } else {
            Result(defaults, defaultScore, defaultScore, atDefaults = true, sessionCount)
        }
    }

    /** MAP objective (higher is better): predictive log-likelihood + lognormal log-priors. */
    private fun mapObjective(logTheta: DoubleArray, history: ReplayHistory, newSnapshot: () -> ReplaySnapshot): Double {
        val config = applyTheta(logTheta)
        val acc = PredictiveScoreAccumulator()
        val engine = ReplayEngine(SessionProgressionStepper(config = config, scorer = acc), config)
        engine.run(history, newSnapshot()) { _, _, _, _, _ -> }
        var logPrior = 0.0
        for (t in logTheta) logPrior += -0.5 * (t / fitConfig.priorSd) * (t / fitConfig.priorSd)
        return acc.total + logPrior
    }
}
