package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.PredictiveDensity

/** Scores all load-bearing sets of ONE session, returning the summed predictive log-score. */
fun interface SetScorer {
    fun sessionScore(setsInSession: List<ScoredSet>): Double
}

/** Marginal Gaussian/censored predictive density with predVar = cleanVar + noiseSd² — the production rule. */
object BaselineScorer : SetScorer {
    override fun sessionScore(setsInSession: List<ScoredSet>): Double =
        setsInSession.sumOf { s ->
            val v = s.cleanVar + s.obs.noiseSd * s.obs.noiseSd
            if (s.obs.gaussianLn != null) {
                PredictiveDensity.gaussianLogDensity(s.obs.gaussianLn, s.predMeanLn, v).toDouble()
            } else {
                PredictiveDensity.censoredLogMass(s.obs.lowerLn, s.obs.upperLn, s.predMeanLn, v).toDouble()
            }
        }
}

/** Sum of per-session scores over the held-out tail (sessionRank >= [minFold]). One-step-ahead: each
 *  session's prediction already reflects only prior sessions, so the tail sum is the held-out score. */
fun heldOutScore(stream: List<ScoredSet>, scorer: SetScorer, minFold: Int = 8): Double =
    stream.filter { it.sessionRank >= minFold }
        .groupBy { it.sessionId }
        .values
        .sumOf { scorer.sessionScore(it) }

data class SweepPoint(val param: Double, val score: Double)
data class InteriorVerdict(val bestParam: Double, val bestScore: Double, val interior: Boolean)

fun sweep(params: List<Double>, score: (Double) -> Double): List<SweepPoint> =
    params.map { SweepPoint(it, score(it)) }

fun interiorVerdict(points: List<SweepPoint>): InteriorVerdict {
    val bestIdx = points.indices.maxByOrNull { points[it].score } ?: -1
    val best = points[bestIdx]
    val interior = bestIdx != 0 && bestIdx != points.lastIndex
    return InteriorVerdict(best.param, best.score, interior)
}
