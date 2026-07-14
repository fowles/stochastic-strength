package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlin.math.exp
import kotlin.math.ln

data class CapViolation(
    val sessionId: Long,
    val exerciseId: Long,
    val predictedE1rm: Float,
    val capE1rm: Float,
)

/**
 * Phase-0 diagnostic for the spec's demonstrated-capacity cap (the Phase-1 policy rule): counts
 * sessions where the stack's held-out prediction exceeded the upper bound demonstrated in that
 * exercise's most recent session (within a 28-day window). Main has no policy layer, so this is
 * baseline color — the "how often would the seatbelt have had to bind" number.
 */
object CapViolationDiagnostic {

    const val CAP_EXPIRY_MS = 28L * 24 * 60 * 60 * 1000

    /** The cap implied by one session's sets for one exercise, in ln(1RM). Null = uncapped. */
    fun capLnFor(sets: List<WorkoutSet>): Float? {
        val intervals = sets.mapNotNull { s ->
            SetIntervals.impliedLn1RmInterval(s)?.let { s to it }
        }
        if (intervals.isEmpty()) return null
        val failed = intervals.filter { (s, _) -> s.feedback == SetFeedback.TOO_HARD }
        if (failed.isNotEmpty()) return failed.mapNotNull { (_, i) -> i.upperLn }.min()
        // Clean session: best demonstrated upper bound; any unbounded set (RIR_5_PLUS) -> no cap.
        val uppers = intervals.map { (_, i) -> i.upperLn }
        if (uppers.any { it == null }) return null
        return uppers.filterNotNull().max()
    }

    fun violations(data: BacktestData): List<CapViolation> {
        data class Cap(val ln: Float, val at: Long)
        val lastCap = mutableMapOf<Long, Cap?>()
        val out = mutableListOf<CapViolation>()
        MainStackReplay.run(data) { sessionId, asOf, sets, predictions, _ ->
            for ((exerciseId, pred) in predictions) {
                val cap = lastCap[exerciseId] ?: continue
                if (asOf - cap.at > CAP_EXPIRY_MS) continue
                if (ln(pred) > cap.ln) out += CapViolation(sessionId, exerciseId, pred, exp(cap.ln))
            }
            sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
                if (exSets.any { it.feedback != null }) {
                    lastCap[id] = capLnFor(exSets)?.let { Cap(it, asOf) }
                }
            }
        }
        return out
    }
}
