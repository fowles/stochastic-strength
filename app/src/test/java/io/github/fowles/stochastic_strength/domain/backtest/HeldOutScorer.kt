package io.github.fowles.stochastic_strength.domain.backtest

import kotlin.math.ln

data class SessionScore(val sessionId: Long, val distance: Double, val scoredSets: Int)

data class ScoreReport(
    val totalDistance: Double,
    val scoredSets: Int,
    /** Sets that implied an interval but had no prediction (cold exercise, no estimate yet). */
    val skippedSets: Int,
    val perSession: List<SessionScore>,
)

/**
 * The single tuning authority (spec Phase 0): forward-chaining held-out score of a stack's
 * predictions against the model-free set intervals. Lower is better; 0 = every prediction landed
 * inside what the user demonstrated.
 */
object HeldOutScorer {
    fun score(data: BacktestData): ScoreReport {
        var total = 0.0
        var scored = 0
        var skipped = 0
        val perSession = mutableListOf<SessionScore>()
        MainStackReplay.run(data) { sessionId, _, sets, predictions, _ ->
            var d = 0.0
            var n = 0
            for (set in sets) {
                val interval = SetIntervals.impliedLn1RmInterval(set) ?: continue
                val pred = predictions[set.exerciseId]
                if (pred == null || pred <= 0f) { skipped++; continue }
                d += interval.distanceTo(ln(pred)).toDouble()
                n++
            }
            total += d
            scored += n
            perSession += SessionScore(sessionId, d, n)
        }
        return ScoreReport(total, scored, skipped, perSession)
    }
}
