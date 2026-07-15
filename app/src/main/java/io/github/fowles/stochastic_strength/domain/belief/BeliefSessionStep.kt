package io.github.fowles.stochastic_strength.domain.belief

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlin.math.exp

/**
 * One session's belief step, shared by the production replay and the backtest replay (spec Phase 3
 * "replay drives the new fold"). Order of operations is the Phase-2 contract and must not change:
 * (1) pre-fold pooling over ALL muscles at asOf — the held-out state and the cold prior;
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
        /** Pre-fold effective beliefs for ALL muscles at asOf — the held-out state; also the cold prior. */
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
        val preFold = mutableMapOf<Long, EffectiveBelief>()
        for ((_, ids) in muscleExerciseIds) {
            preFold.putAll(pooling.effective(beliefs, seedCoef, ids, asOf).effective)
        }

        val touched = mutableSetOf<MuscleGroup>()
        sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
            if ((seedCoef[id] ?: 0f) <= 0f) return@forEach
            val prior = beliefs[id]
                ?: preFold[id]?.let { Belief(it.mu, it.sigma2, asOf) }
                ?: return@forEach
            beliefs[id] = fold.foldSession(prior, exSets, asOf)
            exerciseMuscle[id]?.let { touched.add(it) }
        }

        val steps = touched.mapNotNull { muscle ->
            val ids = muscleExerciseIds[muscle] ?: return@mapNotNull null
            val pool = pooling.effective(beliefs, seedCoef, ids, asOf)
            val level = pool.levelLn?.let { exp(it) } ?: 0f
            val effective = pool.effective.mapValues { (_, e) -> exp(e.mu) }
            val coefs = effective.mapValues { (id, e1rm) ->
                if (level > 0f) e1rm / level else (seedCoef[id] ?: 0f)
            }
            MuscleStep(muscle = muscle, level = level, effectiveE1rm = effective, derivedCoef = coefs)
        }
        return Result(preFoldEffective = preFold, steps = steps)
    }
}
