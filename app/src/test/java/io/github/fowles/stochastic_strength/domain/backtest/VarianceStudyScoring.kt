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

/**
 * Session day-effect: a shared latent offset d ~ N(0, σ_day²) learned sequentially across the session.
 * Each set is scored with predVar = cleanVar + noiseSd² + dVar (day integrated out), then d is updated
 * by a Gaussian Kalman step on the residual (obsLocation − predMean) with obs variance cleanVar+noiseSd².
 * The LEARNING step uses obsLocation for censored sets (moment-match approximation); the SCORE uses the
 * exact censored mass. σ_day = 0 ⇒ dVar = 0 ⇒ the offset never moves ⇒ identical to BaselineScorer.
 */
/**
 * Heavy-tailed observation model: Student-t with [nu] dof and scale sqrt(predVar) (ν→∞ ⇒ Gaussian).
 * Gaussian-point obs use the standardized t log-pdf; censored intervals use the t-interval mass.
 */
class StudentTScorer(private val nu: Double) : SetScorer {
    override fun sessionScore(setsInSession: List<ScoredSet>): Double =
        setsInSession.sumOf { s ->
            val predVar = (s.cleanVar + s.obs.noiseSd * s.obs.noiseSd).toDouble()
            val sd = kotlin.math.sqrt(predVar)
            if (s.obs.gaussianLn != null) {
                val z = (s.obs.gaussianLn - s.predMeanLn) / sd
                StudentT.logPdf(z, nu) - kotlin.math.ln(sd)
            } else {
                val a = s.obs.lowerLn?.let { (it - s.predMeanLn) / sd }?.toDouble()
                val b = s.obs.upperLn?.let { (it - s.predMeanLn) / sd }?.toDouble()
                val loMass = a?.let { StudentT.cdf(it, nu) } ?: 0.0
                val hiMass = b?.let { StudentT.cdf(it, nu) } ?: 1.0
                kotlin.math.ln((hiMass - loMass).coerceAtLeast(1e-12))
            }
        }
}

class DayEffectScorer(private val sigmaDay: Float) : SetScorer {
    override fun sessionScore(setsInSession: List<ScoredSet>): Double {
        var dMean = 0f
        var dVar = sigmaDay * sigmaDay
        var total = 0.0
        val ordered = setsInSession.sortedWith(compareBy({ it.setNumber }, { it.exerciseId }))
        for (s in ordered) {
            val r = s.cleanVar + s.obs.noiseSd * s.obs.noiseSd
            val predMean = s.predMeanLn + dMean
            val predVar = r + dVar
            total += if (s.obs.gaussianLn != null) {
                PredictiveDensity.gaussianLogDensity(s.obs.gaussianLn, predMean, predVar).toDouble()
            } else {
                PredictiveDensity.censoredLogMass(s.obs.lowerLn, s.obs.upperLn, predMean, predVar).toDouble()
            }
            // Kalman update of the day offset from this set's residual about the (offset-free) prediction.
            val y = obsLocation(s.obs) - s.predMeanLn
            val k = dVar / (dVar + r)
            dMean += k * (y - dMean)
            dVar = (1f - k) * dVar
        }
        return total
    }
}
