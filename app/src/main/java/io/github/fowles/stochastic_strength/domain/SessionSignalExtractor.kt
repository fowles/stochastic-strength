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
 * Within one exercise, sets aggregate via a recency EMA over the full-weight sets so the last
 * (most-fatigued) set naturally dominates. A session containing any full-weight failure is capped
 * at zero deviation — it can never grow the weight. When weights change mid-session (a drop), the
 * bracket path handles it, anchoring on the heaviest completed set.
 *
 * Dropped/reduced-weight sets carry no signal; the failure that triggered the drop is itself a
 * full-weight TOO_HARD set and is already captured. HURT carries no load signal.
 */
object SessionSignalExtractor {

    const val RESERVE_RIR_0_1 = 0.5f
    const val RESERVE_RIR_2_4 = 3f
    const val RESERVE_RIR_5_PLUS = 6f

    /** Confidence flag for a demonstrated drop-cascade (failure at top weight + a completed lighter set). */
    const val BRACKET_CONFIDENCE = 0.95f

    /**
     * EMA recency weight for aggregating same-weight sets. Higher = last set dominates more.
     * Tuning surface for last-set dominance vs. multi-set averaging.
     */
    const val RECENCY_BETA = 0.88f

    data class SetSignal(val repDeviation: Float, val confidence: Float, val isFailure: Boolean)

    data class SessionAggregate(
        val est1RM: Float,
        val sessionConfidence: Float,
        val bracketConfidence: Float = 0f,
    )

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

        val topSets = sets.filter { it.targetWeight >= w0 - 1e-3f }
        val droppedSets = sets.filter { it.targetWeight < w0 - 1e-3f }
        val topFailed = topSets.any { it.feedback == SetFeedback.TOO_HARD }
        if (topFailed && droppedSets.isNotEmpty()) {
            return bracketAggregate(sets)
        }

        // Only full-weight sets carry the capacity signal.
        val contributions = sets
            .filter { it.targetWeight >= w0 - 1e-3f }
            .mapNotNull { s -> setSignal(s)?.let { s to it } }
        if (contributions.isEmpty()) return null

        val ordered = contributions.sortedBy { it.first.setNumber }
        val targetReps = ordered.first().first.targetReps

        // Recency EMA across the same-weight sets: the last (most-fatigued) set dominates, so the
        // estimate tracks last-set capacity (where RIR_0_1 should land) rather than the multi-set mean.
        var offset = ordered.first().second.repDeviation
        for (i in 1 until ordered.size) {
            offset = (1f - RECENCY_BETA) * offset + RECENCY_BETA * ordered[i].second.repDeviation
        }
        // Goal 3 safety: a session containing any full-weight failure can never grow the weight.
        if (ordered.any { it.second.isFailure }) offset = minOf(0f, offset)

        val est1RM = DefaultProgressionEngine.rawToOneRepMax(w0, targetReps + offset)
        val sessionConfidence = ordered.maxOf { it.second.confidence }
        return SessionAggregate(est1RM = est1RM, sessionConfidence = sessionConfidence)
    }

    /** Reserve reps implied by a non-failure feedback bucket (reused for the completed-set anchor). */
    private fun reserveReps(feedback: SetFeedback): Float = when (feedback) {
        SetFeedback.RIR_0_1 -> RESERVE_RIR_0_1
        SetFeedback.RIR_2_4 -> RESERVE_RIR_2_4
        SetFeedback.RIR_5_PLUS -> RESERVE_RIR_5_PLUS
        else -> 0f
    }

    /**
     * Capacity estimate when a full-weight failure forced a mid-session drop. Anchor on the heaviest
     * COMPLETED set (capacity demonstrated at a sustainable rep count); failures only cap that anchor
     * from above. If every set failed, estimate from the lightest failed set's achieved reps.
     */
    private fun bracketAggregate(sets: List<WorkoutSet>): SessionAggregate {
        val completed = sets.filter { it.feedback?.isRepsInReserve == true }
        val failed = sets.filter { it.feedback == SetFeedback.TOO_HARD }

        val est1RM = if (completed.isNotEmpty()) {
            val anchor = completed.maxOf { s ->
                DefaultProgressionEngine.rawToOneRepMax(s.targetWeight, s.targetReps + reserveReps(s.feedback!!))
            }
            // A failed weight means target-rep capacity is below it: cap the anchor from above.
            val ceiling = failed.minOf { DefaultProgressionEngine.rawToOneRepMax(it.targetWeight, it.targetReps) }
            minOf(anchor, ceiling)
        } else {
            val lightest = failed.minByOrNull { it.targetWeight }!!
            val reps = lightest.actualReps ?: (lightest.targetReps / 2)
            DefaultProgressionEngine.rawToOneRepMax(lightest.targetWeight, reps.toFloat())
        }
        return SessionAggregate(
            est1RM = est1RM,
            sessionConfidence = BRACKET_CONFIDENCE,
            bracketConfidence = BRACKET_CONFIDENCE,
        )
    }
}
