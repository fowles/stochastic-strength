package io.github.fowles.stochastic_strength.domain.backtest

data class SessionScore(val sessionId: Long, val distance: Double, val scoredSets: Int)

data class ScoreReport(
    val totalDistance: Double,
    val scoredSets: Int,
    /** Sets that implied an interval but had no prediction (cold exercise, no estimate yet). */
    val skippedSets: Int,
    val perSession: List<SessionScore>,
)
