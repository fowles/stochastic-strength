package io.github.fowles.stochastic_strength.domain.belief

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlin.math.exp

/**
 * One session's belief step, shared by the production replay and the backtest replay (spec Phase 3
 * "replay drives the new fold"). Order of operations is the Phase-2 contract and must not change:
 * (1) pre-fold pooling for the muscles this session touches, at asOf — the held-out state for the
 *     session's own sets and the cold prior (pooling is a pure read, so consumers needing other
 *     muscles' effective beliefs pool them directly);
 * (2) per-exercise foldSession, existing belief or the sibling prediction as the cold prior;
 * (3) post-fold pooling for the touched muscles — the derived-state projection.
 */
class BeliefSessionStep(private val config: BeliefConfig) {
    private val fold = BeliefFold(config)
    private val pooling = BeliefPooling(config)

    data class MuscleStep(
        val muscle: MuscleGroup,
        /** exp(levelLn): the muscle level for MuscleGroupStrength/baseline_history (0 if no voters). */
        val level: Float,
        /** exp(mu_eff) per exercise, post-fold pooling at asOf. */
        val effectiveE1rm: Map<Long, Float>,
        /** effectiveE1rm / level, so level × coef == effectiveE1rm (parity with the old projector). */
        val derivedCoef: Map<Long, Float>,
    )

    data class Result(
        /** Pre-fold effective beliefs for the touched muscles at asOf — the held-out state; also the cold prior. */
        val preFoldEffective: Map<Long, EffectiveBelief>,
        /** Post-fold projections for the muscles this session touched. */
        val steps: List<MuscleStep>,
    )

    fun step(
        beliefs: MutableMap<Long, Belief>,
        sets: List<WorkoutSet>,
        seedCoef: Map<Long, Float>,
        exerciseMuscle: Map<Long, MuscleGroup>,
        muscleExerciseIds: Map<MuscleGroup, List<Long>>,
        asOf: Long,
    ): Result {
        val setsByExercise = sets.groupBy { it.exerciseId }
        // Pre-fold pool only the muscles this session's sets touch — all the fold's cold priors and
        // the scorer's held-out predictions live there; pooling other muscles is dead work in a
        // replay that reruns every session after every write.
        val sessionMuscles = setsByExercise.keys.mapNotNullTo(mutableSetOf()) { id ->
            if ((seedCoef[id] ?: 0f) > 0f) exerciseMuscle[id] else null
        }
        val preFold = mutableMapOf<Long, EffectiveBelief>()
        for (muscle in sessionMuscles) {
            val ids = muscleExerciseIds[muscle] ?: continue
            preFold.putAll(pooling.effective(beliefs, seedCoef, ids, asOf).effective)
        }

        // Post-fold steps only for muscles where a fold actually ran (a session against a muscle
        // with no beliefs at all has no prior and projects nothing).
        val touched = mutableSetOf<MuscleGroup>()
        setsByExercise.forEach { (id, exSets) ->
            if ((seedCoef[id] ?: 0f) <= 0f) return@forEach
            val prior = beliefs[id]
                ?: preFold[id]?.let { Belief(it.bestGuessLn, it.uncertainty, asOf) }
                ?: return@forEach
            beliefs[id] = fold.foldSession(prior, exSets, asOf)
            exerciseMuscle[id]?.let { touched.add(it) }
        }

        val steps = touched.mapNotNull { muscle ->
            val ids = muscleExerciseIds[muscle] ?: return@mapNotNull null
            val pool = pooling.effective(beliefs, seedCoef, ids, asOf)
            val level = pool.levelLn?.let { exp(it) } ?: 0f
            val effective = pool.effective.mapValues { (_, e) -> exp(e.bestGuessLn) }
            val coefs = effective.mapValues { (id, e1rm) ->
                if (level > 0f) e1rm / level else (seedCoef[id] ?: 0f)
            }
            MuscleStep(muscle = muscle, level = level, effectiveE1rm = effective, derivedCoef = coefs)
        }
        return Result(preFoldEffective = preFold, steps = steps)
    }
}
