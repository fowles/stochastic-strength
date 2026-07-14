package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import kotlin.math.ln
import kotlin.math.pow

/**
 * Prescription-time policy clamps (spec Phase 1). Constitution rule 6: every rule here is a plain
 * arithmetic restatement of set-log facts — no estimator state, no uncertainty, no learned
 * constants. Constitution rule 3: the constants are semantic gym-language choices, never tuned,
 * and invisible to the backtest fitness function (HeldOutScorer scores the raw estimator).
 */
object PrescriptionPolicy {

    // All constants below are `semantic` (constitution rule 2).

    /** Demonstrated-capacity caps expire 28 days after the session that demonstrated them. */
    const val CAP_EXPIRY_MS = 28L * 24 * 60 * 60 * 1000

    /** A HURT set backs its muscle's prescriptions off by 15%… */
    const val HURT_DEPTH = 0.15f

    /** …fading with a 14-day half-life… */
    const val HURT_HALF_LIFE_MS = 14L * 24 * 60 * 60 * 1000

    /** …and stacked HURT backoffs never push a prescription below 60% of raw. */
    const val HURT_FLOOR = 0.6f

    /** Muscles hard-stressed within 2 days are excluded at planning time (see WorkoutPlanner). */
    const val COOLDOWN_MS = 2L * 24 * 60 * 60 * 1000

    /**
     * The demonstrated-capacity cap implied by ONE session's sets for ONE exercise, in ln(1RM).
     * Null = uncapped (no scoreable feedback, or a clean session containing an unbounded
     * RIR_5_PLUS set). A failed session caps at the failure's implied 1RM — 1RM(w, a+½), the
     * midpoint of the phase-0 bounds table's TOO_HARD interval — min over failed sets; successes
     * in a failed session never lift the cap. A clean session caps at the max demonstrated upper
     * bound, so a narrow success supersedes an older failure ceiling proportionally.
     *
     * NOTE: deliberately stricter than CapViolationDiagnostic.capLnFor (phase-0 baseline
     * artifact, frozen), which uses the interval UPPER bound (a+1) for counted failures.
     */
    fun capLnFor(sessionSets: List<WorkoutSet>): Float? {
        val scoreable = sessionSets.filter {
            it.feedback != null && it.feedback != SetFeedback.HURT && it.targetWeight > 0f
        }
        if (scoreable.isEmpty()) return null
        val failed = scoreable.filter { it.feedback == SetFeedback.TOO_HARD }
        if (failed.isNotEmpty()) {
            return failed.minOf { s ->
                val reps = s.actualReps?.let { it + 0.5f } ?: s.targetReps.toFloat()
                ln(DefaultProgressionEngine.rawToOneRepMax(s.targetWeight, reps))
            }
        }
        val uppers = scoreable.map { SetIntervals.impliedLn1RmInterval(it)?.upperLn }
        if (uppers.any { it == null }) return null
        return uppers.filterNotNull().max()
    }

    /** Multiplicative HURT backoff for a muscle: 1 − depth·2^(−age/halfLife) per event, floored. */
    fun hurtMultiplier(hurtEventTimes: List<Long>, now: Long): Float {
        var m = 1f
        for (t in hurtEventTimes) {
            val age = (now - t).coerceAtLeast(0L)
            m *= 1f - HURT_DEPTH * 0.5f.pow(age.toFloat() / HURT_HALF_LIFE_MS)
        }
        return m.coerceAtLeast(HURT_FLOOR)
    }
}
