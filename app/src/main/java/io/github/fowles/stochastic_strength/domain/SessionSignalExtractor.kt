package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

/**
 * Feedback -> (implied 1RM, confidence) extraction.
 *
 * Each full-weight set collapses to a signed rep-deviation from the target: reps of reserve
 * (positive) for a completed set, or shortfall (negative) for a failure. The planner prescribes the
 * weight doable for exactly `targetReps` reps, so RIR_0_1 — the intended target effort — is only a
 * small up-signal (Option 2: progressive overload, gently), not a large one.
 *
 * Within one exercise, sets aggregate asymmetrically over the full-weight sets, weighted by
 * `confidence x setNumber` so the last (most-fatigued) set dominates:
 *   - no failure: the weighted-mean reserve nudges the baseline up;
 *   - any failure: the worst shortfall dominates, only *softened* by the non-failing sets, and the
 *     result is capped at zero — a session containing a failure can never grow the weight. How much
 *     the good sets soften the down-pull scales with the rep target (low reps strict, high reps
 *     forgiving) via [softening].
 *
 * Dropped/reduced-weight sets carry no signal; the failure that triggered the drop is itself a
 * full-weight TOO_HARD set and is already captured. HURT carries no load signal.
 */
object SessionSignalExtractor {

    const val RESERVE_RIR_0_1 = 0.5f
    const val RESERVE_RIR_2_4 = 3f
    const val RESERVE_RIR_5_PLUS = 6f

    data class SetSignal(val repDeviation: Float, val confidence: Float, val isFailure: Boolean)

    data class SessionAggregate(val est1RM: Float, val sessionConfidence: Float)

    /** Rep-scaled softening of a failure's down-pull: strict at low reps, forgiving at high reps. */
    fun softening(reps: Int): Float = 0.10f + 0.70f * (reps.coerceIn(1, 20) - 1) / 19f

    fun setSignal(set: WorkoutSet): SetSignal? {
        val feedback = set.feedback ?: return null
        return when (feedback) {
            SetFeedback.HURT -> null
            SetFeedback.RIR_5_PLUS -> SetSignal(RESERVE_RIR_5_PLUS, 0.4f, isFailure = false)
            SetFeedback.RIR_2_4 -> SetSignal(RESERVE_RIR_2_4, 0.7f, isFailure = false)
            SetFeedback.RIR_0_1 -> SetSignal(RESERVE_RIR_0_1, 0.85f, isFailure = false)
            SetFeedback.TOO_HARD -> {
                val reps = set.actualReps
                val shortfall = if (reps != null) (reps - set.targetReps).toFloat() else -(set.targetReps / 2f)
                SetSignal(shortfall, 0.95f, isFailure = true)
            }
        }
    }

    fun aggregateSession(sets: List<WorkoutSet>): SessionAggregate? {
        if (sets.isEmpty()) return null
        val w0 = sets.maxOf { it.targetWeight }
        if (w0 <= 0f) return null

        // Only full-weight sets carry the capacity signal.
        val contributions = sets
            .filter { it.targetWeight >= w0 - 1e-3f }
            .mapNotNull { s -> setSignal(s)?.let { s to it } }
        if (contributions.isEmpty()) return null

        val targetReps = contributions.first().first.targetReps
        fun weightOf(s: WorkoutSet, sig: SetSignal) = sig.confidence * s.setNumber

        val reserves = contributions.filter { !it.second.isFailure }
        val fails = contributions.filter { it.second.isFailure }

        val upWsum = reserves.sumOf { weightOf(it.first, it.second).toDouble() }.toFloat()
        val upAgg = if (upWsum > 0f) {
            reserves.sumOf { (it.second.repDeviation * weightOf(it.first, it.second)).toDouble() }
                .toFloat() / upWsum
        } else {
            0f
        }

        val aggOffset = if (fails.isEmpty()) {
            upAgg
        } else {
            val worstFail = fails.minOf { it.second.repDeviation }
            minOf(0f, worstFail + softening(targetReps) * upAgg)
        }

        val est1RM = DefaultProgressionEngine.rawToOneRepMax(w0, targetReps + aggOffset)
        val sessionConfidence = contributions.maxOf { it.second.confidence }
        return SessionAggregate(est1RM = est1RM, sessionConfidence = sessionConfidence)
    }
}
