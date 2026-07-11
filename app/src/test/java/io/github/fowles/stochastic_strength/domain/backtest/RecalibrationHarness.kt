package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import io.github.fowles.stochastic_strength.domain.progression.FitConfig
import io.github.fowles.stochastic_strength.domain.progression.HyperparameterFitter
import io.github.fowles.stochastic_strength.domain.progression.PredictiveScoreAccumulator
import io.github.fowles.stochastic_strength.domain.progression.ReplayEngine
import io.github.fowles.stochastic_strength.domain.progression.ReplayHistory
import io.github.fowles.stochastic_strength.domain.progression.SessionProgressionStepper

/**
 * Phase-5 offline recalibration: forward-chaining cross-validation over real histories to
 * propose new global defaults for the four fitted estimator hyperparameters. Analysis-only,
 * test-tree; changes no production constant (adoption is a separate human-gated step).
 */
object RecalibrationHarness {

    data class UserHistory(
        val history: ReplayHistory,
        val newSnapshot: () -> ReplaySnapshot,
    )

    data class FoldRow(
        val k: Int,
        val multipliers: DoubleArray,
        val heldOutProposed: Double,
        val heldOutDefault: Double,
    )

    private val DEFAULTS = EstimatorConfig()

    /** First [k] completed sessions (ordered by endTime), with only their sets/overrides. */
    fun truncateTo(history: ReplayHistory, k: Int): ReplayHistory {
        val kept = history.sessions
            .sortedBy { it.endTime ?: Long.MAX_VALUE }
            .take(k)
        val keptIds = kept.map { it.id }.toSet()
        return history.copy(
            sessions = kept,
            setsBySession = history.setsBySession.filterKeys { it in keptIds },
            sessionOverrides = history.sessionOverrides.filterKeys { it in keptIds },
        )
    }

    /** Sum of one-step-ahead predictive log-scores over a replay of [history] under [config]. */
    fun scoredReplayTotal(
        history: ReplayHistory,
        config: EstimatorConfig,
        newSnapshot: () -> ReplaySnapshot,
    ): Double {
        val acc = PredictiveScoreAccumulator()
        val engine = ReplayEngine(
            SessionProgressionStepper(config = config, scorer = acc),
            config,
        )
        engine.run(history, newSnapshot()) { _, _, _, _, _ -> }
        return acc.total
    }

    /** Multipliers of a fitted config over the defaults, in applyTheta order (drift,fatigue,procNoise,tau). */
    private fun multipliersOf(c: EstimatorConfig): DoubleArray = doubleArrayOf(
        (c.detrainRatePerWeek / DEFAULTS.detrainRatePerWeek).toDouble(),
        (c.fatiguePerSet / DEFAULTS.fatiguePerSet).toDouble(),
        (c.processNoisePerDay / DEFAULTS.processNoisePerDay).toDouble(),
        (c.tauBarbell / DEFAULTS.tauBarbell).toDouble(),
    )

    /**
     * Forward-chaining CV over one user history. For each fold k in [minFoldSessions, N-1],
     * fit θ on sessions[1..k] and score the held-out one-step-ahead prediction of session k+1
     * by differencing scored replays of [1..k+1] and [1..k] under the same θ. Default θ scored
     * the same way is the honest baseline.
     */
    enum class Flag { STABLE, PINS_BOUND, FRAGILE }

    data class ParamVerdict(
        val name: String,
        val trajectory: List<Double>,
        val proposedMultiplier: Double,
        val cvDelta: Double,
        val flag: Flag,
    )

    data class RecalibrationReport(
        val sessionCount: Int,
        val foldCount: Int,
        val params: List<ParamVerdict>,
        val cvTotalProposed: Double,
        val cvTotalDefault: Double,
    )

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val idx = (p * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
        val lo = idx.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = idx - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }

    private fun median(xs: List<Double>): Double = percentile(xs.sorted(), 0.5)

    /** Later half of the trajectory (most data); at least the last element. */
    private fun matureHalf(trajectory: List<Double>): List<Double> {
        if (trajectory.isEmpty()) return trajectory
        val from = trajectory.size / 2
        return trajectory.subList(from, trajectory.size)
    }

    fun classify(trajectory: List<Double>, loBound: Double, hiBound: Double): Flag {
        val mature = matureHalf(trajectory)
        if (mature.isEmpty()) return Flag.FRAGILE
        val atBound = mature.count { m ->
            kotlin.math.abs(m - loBound) <= 0.01 * loBound || kotlin.math.abs(m - hiBound) <= 0.01 * hiBound
        }
        if (atBound * 2 >= mature.size) return Flag.PINS_BOUND
        val sorted = mature.sorted()
        val med = median(mature)
        val spread = if (med == 0.0) Double.MAX_VALUE else (percentile(sorted, 0.75) - percentile(sorted, 0.25)) / med
        return if (spread <= 0.25) Flag.STABLE else Flag.FRAGILE
    }

    /**
     * Assembles fold rows into a report. Deliberately does NOT take a [UserHistory]: every field
     * here is derived from [rows] alone, so a multi-user caller can pool rows across users without
     * any single user's identity leaking in. [RecalibrationReport.sessionCount] is left 0 — the
     * caller owns it (it is a cross-user total, not a per-fold quantity) and must set it via
     * `.copy(sessionCount = ...)` (see [runHarness]). Do NOT reintroduce a user param to compute
     * new fields here without first teaching [runHarness] to aggregate them across users.
     */
    fun assemble(rows: List<FoldRow>, loBound: Double, hiBound: Double): RecalibrationReport {
        val names = listOf("drift", "fatigue", "procNoise", "tau")
        val verdicts = names.mapIndexed { i, name ->
            val trajectory = rows.map { it.multipliers[i] }
            ParamVerdict(
                name = name,
                trajectory = trajectory,
                proposedMultiplier = median(matureHalf(trajectory)),
                cvDelta = rows.sumOf { it.heldOutProposed } - rows.sumOf { it.heldOutDefault },
                flag = classify(trajectory, loBound, hiBound),
            )
        }
        return RecalibrationReport(
            sessionCount = 0,
            foldCount = rows.size,
            params = verdicts,
            cvTotalProposed = rows.sumOf { it.heldOutProposed },
            cvTotalDefault = rows.sumOf { it.heldOutDefault },
        )
    }

    fun harnessFitConfig(): FitConfig = FitConfig(
        minFitSessions = 8,
        // Low bound roomy (fatigue wants ~0.13× and some folds go lower); high bound set to the
        // out-of-sample CV-optimal cap. A bound sweep (2026-07-11) showed procNoise has NO interior —
        // it pins whatever cap it is given (obs-noise is pinned low by design, so unexplained
        // session variance dumps into procNoise). Held-out CV delta vs default peaks at cap ×16
        // (+31.3), plateaus down to ×8 (+30.6), and degrades beyond (×32 +28.3, ×64 +23.3): widening
        // overfits. So the cap is a regularization choice, and ×16 is the CV-max operating point.
        boundMultiplierLo = 1.0 / 64.0,
        boundMultiplierHi = 16.0,
        priorSd = 1.5,
        maxIterations = 200,
    )

    fun runHarness(users: List<UserHistory>, minFoldSessions: Int = 8): RecalibrationReport {
        val fitConfig = harnessFitConfig()
        val allRows = users.flatMap { user ->
            foldScores(user, minFoldSessions) { train ->
                HyperparameterFitter(DEFAULTS, fitConfig)
                    .fit(train) { user.newSnapshot() }
                    .config
            }
        }
        return assemble(allRows, fitConfig.boundMultiplierLo, fitConfig.boundMultiplierHi)
            .copy(sessionCount = users.sumOf { u -> u.history.sessions.count { s -> s.endTime != null } })
    }

    fun format(report: RecalibrationReport): String {
        val sb = StringBuilder()
        sb.appendLine("Phase-5 recalibration report")
        sb.appendLine("sessions=${report.sessionCount} folds=${report.foldCount}")
        sb.appendLine("CV total: proposed=${"%.3f".format(report.cvTotalProposed)} default=${"%.3f".format(report.cvTotalDefault)} delta=${"%.3f".format(report.cvTotalProposed - report.cvTotalDefault)}")
        sb.appendLine("param      proposed×  flag        trajectory")
        for (p in report.params) {
            val traj = p.trajectory.joinToString(",") { "%.2f".format(it) }
            sb.appendLine("%-10s %-9s %-11s %s".format(p.name, "%.3f".format(p.proposedMultiplier), p.flag, traj))
        }
        return sb.toString()
    }

    /** A candidate default config as multipliers on the four fitted params (order: drift, fatigue, procNoise, tau). */
    fun configWithMultipliers(drift: Double, fatigue: Double, procNoise: Double, tau: Double): EstimatorConfig =
        DEFAULTS.copy(
            detrainRatePerWeek = (DEFAULTS.detrainRatePerWeek * drift).toFloat(),
            fatiguePerSet = (DEFAULTS.fatiguePerSet * fatigue).toFloat(),
            processNoisePerDay = (DEFAULTS.processNoisePerDay * procNoise).toFloat(),
            tauBarbell = (DEFAULTS.tauBarbell * tau).toFloat(),
            tauMachineCable = (DEFAULTS.tauMachineCable * tau).toFloat(),
            tauOtherLoaded = (DEFAULTS.tauOtherLoaded * tau).toFloat(),
        )

    /**
     * Out-of-sample predictive score on the held-out tail (sessions minFoldSessions+1 .. N) under a
     * FIXED config (no per-fold refit). With a fixed config the forward-chaining held-out sum
     * telescopes to score(full) − score(first minFoldSessions), i.e. the predictive score of the
     * held-out tail — so a fixed-config default candidate can be evaluated with two replays.
     */
    fun heldOutTailScore(user: UserHistory, config: EstimatorConfig, minFoldSessions: Int = 8): Double =
        scoredReplayTotal(user.history, config, user.newSnapshot) -
            scoredReplayTotal(truncateTo(user.history, minFoldSessions), config, user.newSnapshot)

    fun foldScores(
        user: UserHistory,
        minFoldSessions: Int = 8,
        fit: (ReplayHistory) -> EstimatorConfig,
    ): List<FoldRow> {
        val n = user.history.sessions.count { it.endTime != null }
        val rows = mutableListOf<FoldRow>()
        for (k in minFoldSessions..(n - 1)) {
            val train = truncateTo(user.history, k)
            val trainPlus = truncateTo(user.history, k + 1)
            val theta = fit(train)
            fun heldOut(config: EstimatorConfig): Double =
                scoredReplayTotal(trainPlus, config, user.newSnapshot) -
                    scoredReplayTotal(train, config, user.newSnapshot)
            rows += FoldRow(
                k = k,
                multipliers = multipliersOf(theta),
                heldOutProposed = heldOut(theta),
                heldOutDefault = heldOut(DEFAULTS),
            )
        }
        return rows
    }
}
