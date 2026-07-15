package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.policy.SetIntervals

/**
 * Held-out score of the BELIEF stack (same authority metric as HeldOutScorer, same intervals,
 * per-SET predictions). Scores the RAW estimator — pre-z, pre-clamp (constitution rule 6).
 * [coveredSets] (= sets landing inside their interval) is a supplementary report only.
 */
object BeliefHeldOutScorer {
    data class BeliefScoreReport(val report: ScoreReport, val coveredSets: Int)

    fun score(data: BacktestData, config: BeliefConfig): BeliefScoreReport {
        var total = 0.0
        var scored = 0
        var skipped = 0
        var covered = 0
        val perSession = mutableListOf<SessionScore>()
        BeliefStackReplay.run(data, config) { sessionId, _, predictions, _, _ ->
            var d = 0.0
            var n = 0
            for (p in predictions) {
                val interval = SetIntervals.impliedLn1RmInterval(p.set) ?: continue
                val pred = p.predictedLn
                if (pred == null) { skipped++; continue }
                val dist = interval.distanceTo(pred).toDouble()
                if (dist == 0.0) covered++
                d += dist
                n++
            }
            total += d
            scored += n
            perSession += SessionScore(sessionId, d, n)
        }
        return BeliefScoreReport(ScoreReport(total, scored, skipped, perSession), covered)
    }
}
