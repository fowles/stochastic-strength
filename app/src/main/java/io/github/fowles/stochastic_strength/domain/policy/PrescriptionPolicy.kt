package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.ProgressionEngine
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * The prescription policy (spec §4): every training decision between the projected belief and
 * the weight on the bar. Phase 1 scope: neutral z/δ knobs, failure ceiling, HURT caution,
 * sore-muscle cooldown. Fatigue discount and layoff easing arrive with the belief swap (phase 2).
 * Pure and read-only; all inputs derive from replayed history.
 */
class PrescriptionPolicy(
    private val pooledE1rm: Map<Long, Float>,
    private val state: PolicyState,
    private val config: EstimatorConfig = EstimatorConfig(),
    private val progressionEngine: ProgressionEngine,
    private val weightUnit: WeightUnit,
    private val nowMs: Long,
) {

    /** Final session weight in kg for a loadable exercise, or null when nothing is known about it. */
    fun prescribe(exercise: Exercise, sessionReps: Int): Float? {
        val pooled = pooledE1rm[exercise.id] ?: return null
        if (pooled <= 0f) return null

        var targetE1rm = exp(ln(pooled) + config.overloadDelta) // z·σ̃ joins in phase 2

        // Failure ceiling first (spec §4 order): the cap is on demonstrated capacity, so the
        // HURT caution below compounds under it rather than being floored by it.
        var clearCeiling = false
        var failedWeightAtReps = Float.MAX_VALUE
        val ceiling = state.ceilings[exercise.id]
        if (ceiling != null && nowMs - ceiling.sessionEndTime <= config.ceilingExpiryMs) {
            val cap = ceiling.ceilingE1rm * (if (ceiling.isClear) config.ceilingFactorClear else 1f)
            if (targetE1rm > cap) targetE1rm = cap
            if (ceiling.isClear) {
                clearCeiling = true
                failedWeightAtReps = progressionEngine.fromOneRepMax(ceiling.ceilingE1rm, sessionReps)
            }
        }

        targetE1rm *= hurtMultiplier(exercise.primaryMuscle)

        val raw = progressionEngine.fromOneRepMax(targetE1rm, sessionReps)
        val nearest = WeightFormatter.round(raw, weightUnit)
        // A CLEAR ceiling guarantees strictly-below-the-failed-weight even after grid rounding:
        // when nearest-rounding would land at/above the failed weight's equivalent at these reps
        // (possible on coarse grids for light lifts, since the 3% haircut can be under half a grid
        // step), round down instead. Far-below-cap targets keep nearest rounding.
        return if (clearCeiling && nearest >= failedWeightAtReps) WeightFormatter.roundDown(raw, weightUnit)
        else nearest
    }

    /** Combined HURT caution for a muscle: recent events multiply in, decaying with a half-life. */
    fun hurtMultiplier(muscle: MuscleGroup): Float {
        var m = 1f
        for (event in state.hurtEvents) {
            if (event.muscle != muscle) continue
            val age = (nowMs - event.at).coerceAtLeast(0L)
            m *= 1f - config.hurtDepth * 0.5f.pow(age.toFloat() / config.hurtHalfLifeMs)
        }
        return m.coerceAtLeast(config.hurtFloor)
    }

    /**
     * Sore-muscle cooldown (verbatim port of WorkoutPlanner.recentlyFailedMuscles): a muscle is
     * NOT rested when, within the window, a loaded exercise had any TOO_HARD or >1 RIR_0_1 set.
     * Known micro-deltas vs the old planner rule (all conservative): abandoned sessions no longer
     * feed stress; timestamp-less sets count at session end time; stress accrues even from
     * exercises not currently plannable (disliked/location-excluded).
     */
    fun muscleRested(muscle: MuscleGroup): Boolean {
        val stress = state.muscleStress[muscle] ?: return true
        val cutoff = nowMs - config.restCooldownMs
        val anyTooHard = stress.tooHardTimes.any { it >= cutoff }
        val nearLimit = stress.rir01TimesByExercise.any { (_, times) -> times.count { it >= cutoff } > 1 }
        return !(anyTooHard || nearLimit)
    }
}
