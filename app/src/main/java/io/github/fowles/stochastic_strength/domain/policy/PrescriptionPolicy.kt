package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.ProgressionEngine
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * One clamped prescription. Everything the policy decided is reported here so consumers (the
 * clamp-bind health report, the "why this weight" trace) read what happened instead of
 * re-deriving it.
 */
data class Prescription(
    val weightKg: Float,
    val capBound: Boolean,
    val hurtMultiplier: Float,
    /** The rounded (and nudged) weight the engine wanted BEFORE the cap — == [weightKg] unless the cap bound. */
    val uncappedWeightKg: Float,
    /** The live cap expressed at the session's reps (raw rep-max inverse, un-rounded); null = no live cap. */
    val capWeightKg: Float? = null,
    /** The overload nudge added to the rounded uncapped weight; 0 when it didn't apply. */
    val nudgeKg: Float = 0f,
)

/**
 * Prescription-time policy clamps (spec Phase 1). Constitution rule 6: every rule here is a plain
 * arithmetic restatement of set-log facts — no estimator state, no uncertainty, no learned
 * constants. Constitution rule 3: the constants are semantic gym-language choices, never tuned,
 * and invisible to the backtest fitness function (BeliefHeldOutScorer scores the raw estimator).
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
     * How far back the set log must be read when building [PolicyFacts]: caps need exactly
     * [CAP_EXPIRY_MS] (older demonstrations are expired anyway), and a HURT backoff beyond
     * 4 half-lives contributes under 1% — the window covers both exactly/negligibly.
     */
    const val FACTS_WINDOW_MS = 4 * HURT_HALF_LIFE_MS // 56 days ≥ CAP_EXPIRY_MS

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

    /**
     * prescribe(rawTarget, PolicyFacts) → weight (spec Phase 1). Order: HURT backoff multiplies the
     * raw target, then the demonstrated-capacity cap ceilings it, then grid rounding. When the cap
     * binds, the weight is computed with the RAW rep-max inverse and floor-rounded at the grid —
     * pre-rounding to the 0.5 kg internal grid could nudge the weight back up to exactly the failed
     * weight, and nearest-rounding at the prescription grid could round above the cap. The cap
     * comparison happens AFTER grid rounding, in weight space — a raw estimate just under the cap in
     * log space can still nearest-round up to (or past) the capped weight, so the cap must bind on
     * the final rounded prescription, not the pre-rounding log estimate.
     *
     * Overload nudge: when the exercise's most recent feedback session was entirely RIR ≥ 2
     * (`allEasy`) and still within the cap's expiry window, bump the rounded uncapped weight by one
     * grid increment (semantic: the smallest available plate) before the cap comparison — the
     * demonstrated-capacity cap still applies on top and can clamp the nudge away. The belief
     * stack's in-band feedback legitimately leaves bestGuessLn unmoved, so this is the steady-state
     * progressive-overload rule (spec Phase 2).
     */
    fun prescribe(
        rawE1rm: Float,
        sessionReps: Int,
        exerciseId: Long,
        muscle: MuscleGroup,
        facts: PolicyFacts,
        now: Long,
        weightUnit: WeightUnit,
        engine: ProgressionEngine,
    ): Prescription {
        val mult = hurtMultiplier(facts.hurtEventsByMuscle[muscle].orEmpty(), now)
        val backed = rawE1rm * mult
        val fact = facts.capByExercise[exerciseId]
        val withinWindow = fact != null && now - fact.demonstratedAt <= CAP_EXPIRY_MS
        val capLn = fact?.capLn?.takeIf { withinWindow }
        // `withinWindow` implies `fact != null`, so `fact` smart-casts non-null here.
        val nudge = if (withinWindow && fact.allEasy) WeightFormatter.minIncrement(weightUnit) else 0f
        val uncapped = WeightFormatter.round(engine.fromOneRepMax(backed, sessionReps), weightUnit) + nudge
        if (capLn == null) {
            return Prescription(uncapped, capBound = false, hurtMultiplier = mult, uncappedWeightKg = uncapped, nudgeKg = nudge)
        }
        // The cap is a ceiling on the FINAL prescription: nearest-grid rounding of a
        // just-under-cap estimate must not climb back to a weight the cap excludes, so the
        // comparison happens after rounding, in weight space. The cap weight itself comes from
        // the RAW rep-max inverse (the engine's 0.5 kg internal rounding could nudge it up),
        // and a binding cap floor-rounds at the grid.
        val capWeight = engine.rawFromOneRepMax(exp(capLn), sessionReps)
        if (uncapped <= capWeight + WeightFormatter.GRID_EPSILON) {
            return Prescription(uncapped, capBound = false, hurtMultiplier = mult, uncappedWeightKg = uncapped, capWeightKg = capWeight, nudgeKg = nudge)
        }
        return Prescription(
            WeightFormatter.roundDown(capWeight, weightUnit),
            capBound = true, hurtMultiplier = mult, uncappedWeightKg = uncapped,
            capWeightKg = capWeight, nudgeKg = nudge,
        )
    }
}
