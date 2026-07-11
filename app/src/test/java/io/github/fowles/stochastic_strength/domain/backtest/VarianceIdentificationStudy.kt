package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig

data class CandidateResult(
    val name: String, val bestParam: Double, val heldOut: Double,
    val deltaVsB0: Double, val deltaVsB1: Double, val interior: Boolean, val points: List<SweepPoint>,
)

data class StudyReport(
    val b0: Double, val b1: Double, val candidates: List<CandidateResult>,
    val anchorSweep: List<SweepPoint>,
    val decomposition: ResidualDecomposition, val pairCorrelations: List<PairCorrelation>,
    val b0Swing: LightLiftSwing?, val b1Swing: LightLiftSwing?,
)

/**
 * Phase-6 variance-identification study: CV-scores the four candidate variance structures against the
 * B0 (default) and B1 (procNoise x16) references on real history, reports interior-optimum status and
 * diagnostics, and ranks a recommendation. Analysis-only; changes no production constant.
 */
object VarianceIdentificationStudy {
    private const val RECOMMEND_MIN_GAIN = 2.0  // nats of held-out log-score; below this a "gain" is noise
    private val SIGMA_DAY = listOf(0.0, 0.02, 0.04, 0.06, 0.08, 0.10, 0.14, 0.18, 0.24)
    private val OBS_NOISE = listOf(0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0)
    private val TAU = listOf(0.25, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0)
    private val NU = listOf(2.5, 4.0, 6.0, 10.0, 20.0, 50.0, 1e6)
    private val ANCHOR = listOf(0.25, 0.5, 1.0, 2.0, 4.0)

    fun run(data: BacktestHarness.BacktestData, minFold: Int = 8): StudyReport {
        val base = EstimatorConfig()
        val baseStream = captureStream(data.history, base, data::newSnapshot)
        val b0 = heldOutScore(baseStream, BaselineScorer, minFold)
        val b1 = heldOutScore(
            captureStream(data.history, VarianceStudyConfigs.withProcNoise(base, 16.0), data::newSnapshot),
            BaselineScorer, minFold,
        )

        fun candidate(name: String, points: List<SweepPoint>): CandidateResult {
            val v = interiorVerdict(points)
            return CandidateResult(name, v.bestParam, v.bestScore, v.bestScore - b0, v.bestScore - b1, v.interior, points)
        }

        // Day-effect: default belief evolution, day-effect scoring, sweep sigma_day (scorer-only).
        val dayEffect = candidate("day-effect", sweep(SIGMA_DAY) { sd ->
            heldOutScore(baseStream, DayEffectScorer(sd.toFloat()), minFold)
        })
        // Obs-noise: config change, baseline scoring — re-capture per multiplier.
        val obsNoise = candidate("obs-noise", sweep(OBS_NOISE) { m ->
            heldOutScore(captureStream(data.history, VarianceStudyConfigs.withObsNoise(base, m), data::newSnapshot), BaselineScorer, minFold)
        })
        // Student-t: default belief evolution, t-scoring, sweep nu (scorer-only).
        val studentT = candidate("student-t", sweep(NU) { nu ->
            heldOutScore(baseStream, StudentTScorer(nu), minFold)
        })
        // Transfer: config change (tau), baseline scoring — re-capture per multiplier.
        val transfer = candidate("transfer-tau", sweep(TAU) { m ->
            heldOutScore(captureStream(data.history, VarianceStudyConfigs.withTau(base, m), data::newSnapshot), BaselineScorer, minFold)
        })

        // Anchor-precision mini-sweep (spec §5.4): config change, baseline scoring — diagnostic, not a named candidate.
        val anchorSweep = sweep(ANCHOR) { m ->
            heldOutScore(captureStream(data.history, VarianceStudyConfigs.withAnchorPrecision(base, m), data::newSnapshot), BaselineScorer, minFold)
        }

        val b0Rows = BacktestHarness.replayPolicyPrescriptions(data, base)
        val b1Rows = BacktestHarness.replayPolicyPrescriptions(data, VarianceStudyConfigs.withProcNoise(base, 16.0))

        return StudyReport(
            b0 = b0, b1 = b1,
            candidates = listOf(dayEffect, obsNoise, studentT, transfer),
            anchorSweep = anchorSweep,
            decomposition = decomposeResiduals(baseStream),
            pairCorrelations = sameMusclePairCorrelations(baseStream),
            b0Swing = lightestLiftSwing(b0Rows),
            b1Swing = lightestLiftSwing(b1Rows),
        )
    }

    fun format(r: StudyReport): String {
        val sb = StringBuilder()
        sb.appendLine("Variance-identification study")
        sb.appendLine("references: B0(default)=%.3f  B1(procNoise x16)=%.3f  (B1-B0=%.3f)".format(r.b0, r.b1, r.b1 - r.b0))
        sb.appendLine()
        sb.appendLine("candidate      best@     heldOut   dVsB0    dVsB1    interior")
        for (c in r.candidates) {
            sb.appendLine("%-14s %-9s %-9s %-8s %-8s %s".format(
                c.name, "%.3f".format(c.bestParam), "%.3f".format(c.heldOut),
                "%+.3f".format(c.deltaVsB0), "%+.3f".format(c.deltaVsB1), if (c.interior) "INTERIOR" else "pins-bound"))
        }
        sb.appendLine()
        for (c in r.candidates) {
            sb.appendLine("  ${c.name} sweep: " + c.points.joinToString(" ") { "%.3f=%.2f".format(it.param, it.score) })
        }
        sb.appendLine("  anchor-precision sweep: " + r.anchorSweep.joinToString(" ") { "%.3f=%.2f".format(it.param, it.score) })
        sb.appendLine()
        val d = r.decomposition
        sb.appendLine("residuals: n=%d totalVar=%.4f betweenSession=%.4f withinSession=%.4f (between share=%.1f%%)".format(
            d.n, d.totalVar, d.betweenSessionVar, d.withinSessionVar,
            if (d.totalVar > 0) 100.0 * d.betweenSessionVar / d.totalVar else 0.0))
        sb.appendLine("same-muscle pair correlations:")
        for (p in r.pairCorrelations.sortedByDescending { it.correlation }) {
            sb.appendLine("  %s ex%d~ex%d  r=%.2f (n=%d)".format(p.muscle, p.exerciseA, p.exerciseB, p.correlation, p.nSessions))
        }
        sb.appendLine()
        sb.appendLine("light-lift swing (deferred color): B0=${r.b0Swing}  B1=${r.b1Swing}")
        sb.appendLine()
        sb.appendLine("RECOMMENDATION (CV gain + interior optimum; see spec decision gate):")
        val ranked = r.candidates.sortedWith(compareByDescending<CandidateResult> { it.interior }.thenByDescending { it.deltaVsB0 })
        for (c in ranked) {
            val verdict = when {
                c.deltaVsB0 > RECOMMEND_MIN_GAIN && c.interior -> "RECOMMENDED (beats B0 by >%.0f nat with interior optimum)".format(RECOMMEND_MIN_GAIN)
                c.deltaVsB0 > 0 && c.interior -> "interior but negligible gain (<%.0f nat over B0)".format(RECOMMEND_MIN_GAIN)
                c.deltaVsB0 > 0 -> "gains but pins bound (release-valve-like, treat with suspicion)"
                else -> "no CV gain over B0"
            }
            sb.appendLine("  %-14s dVsB0=%+.3f dVsB1=%+.3f -> %s".format(c.name, c.deltaVsB0, c.deltaVsB1, verdict))
        }
        return sb.toString()
    }
}
