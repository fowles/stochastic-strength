package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefFold
import io.github.fowles.stochastic_strength.domain.belief.BeliefPooling
import io.github.fowles.stochastic_strength.domain.belief.EffectiveBelief
import kotlin.math.ln

/**
 * Forward-chained replay of the BELIEF stack over parsed backup data (spec Phase 2). Mirrors
 * MainStackReplay's session semantics exactly (override rows seed/reset beliefs; session-k
 * overrides apply before session k; sessions sorted by (endTime, id); empty-set sessions skip) —
 * KEEP IN SYNC with MainStackReplay. Predictions are per SET (fatigue-aware), captured pre-fold.
 *
 * Cold exercises (no belief yet) fold their first session against the sibling prediction as the
 * prior — the pool is the prior, no extra constant (spec: cold exercises lean on siblings).
 */
object BeliefStackReplay {

    data class SetPrediction(val set: WorkoutSet, val rank: Int, val predictedLn: Float?)

    fun interface SessionObserver {
        fun onSession(
            sessionId: Long,
            asOf: Long,
            predictions: List<SetPrediction>,
            effective: Map<Long, EffectiveBelief>,
            beliefs: Map<Long, Belief>,
        )
    }

    fun run(data: BacktestData, config: BeliefConfig, observer: SessionObserver) {
        val fold = BeliefFold(config)
        val pooling = BeliefPooling(config)
        val snapshot = data.newSnapshot()
        val beliefs = mutableMapOf<Long, Belief>()
        val sigmaSeed2 = config.sigmaSeed * config.sigmaSeed
        val sigmaOverride2 = config.sigmaOverride * config.sigmaOverride

        for (init in data.initialOverrides) {
            beliefs[init.exerciseId] = Belief(ln(init.e1rm), sigmaSeed2, init.asOf)
        }
        for (session in data.sessions) {
            data.sessionOverrides[session.id]?.forEach { o ->
                beliefs[o.exerciseId] = Belief(ln(o.e1rm), sigmaOverride2, o.asOf)
            }
            val sets = data.setsBySession[session.id].orEmpty()
            if (sets.isEmpty()) continue
            val asOf = session.endTime!!

            // Pre-fold effective beliefs for every muscle at asOf = the held-out state.
            val effective = mutableMapOf<Long, EffectiveBelief>()
            for ((_, ids) in snapshot.muscleExerciseIds) {
                effective.putAll(pooling.effective(beliefs, snapshot.seedCoefficients, ids, asOf).effective)
            }
            // Per-set predictions: rank over ALL of the exercise's rows (id order), fatigue-shifted.
            val predictions = sets.groupBy { it.exerciseId }.flatMap { (id, exSets) ->
                val eff = effective[id]
                exSets.sortedBy { it.id }.mapIndexed { idx, s ->
                    SetPrediction(s, idx + 1, eff?.let { it.mu - fold.fatigueShift(idx + 1) })
                }
            }
            // Fold: existing belief, or sibling prediction as the cold prior.
            sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
                if ((snapshot.seedCoefficients[id] ?: 0f) <= 0f) return@forEach
                val prior = beliefs[id]
                    ?: effective[id]?.let { Belief(it.mu, it.sigma2, asOf) }
                    ?: return@forEach
                beliefs[id] = fold.foldSession(prior, exSets, asOf)
            }
            observer.onSession(session.id, asOf, predictions, effective, beliefs)
        }
    }
}
