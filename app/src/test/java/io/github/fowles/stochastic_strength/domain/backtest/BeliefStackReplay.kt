package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefFold
import io.github.fowles.stochastic_strength.domain.belief.BeliefPooling
import io.github.fowles.stochastic_strength.domain.belief.EffectiveBelief
import io.github.fowles.stochastic_strength.domain.progression.ReplayEngine
import kotlinx.coroutines.runBlocking

/**
 * Forward-chained replay of the BELIEF stack over parsed backup data (spec Phase 2). Delegates to
 * the production [ReplayEngine.runCore], so seeding/ordering semantics (override rows seed/reset
 * beliefs; session-k overrides apply before session k; sessions sorted by (endTime, id);
 * empty-set sessions skip) are literally the production path, not a mirror of it. Predictions are
 * per SET (fatigue-aware), captured pre-fold.
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

        // All-muscle pre-fold effective beliefs for the observer (the policy backtest prescribes
        // for the whole library each session). The prod step scopes its pre-fold pooling to the
        // session's muscles, so the sweep lives here, refreshed by the pre-fold hook.
        var allEffective: Map<Long, EffectiveBelief> = emptyMap()

        runBlocking {
            ReplayEngine(config).runCore(
                snapshot = snapshot,
                initialOverrides = data.initialOverrides,
                sessionOverrides = data.sessionOverrides,
                sessions = data.sessions,
                setsForSession = { data.setsBySession[it].orEmpty() },
                beforeSession = { beliefs, asOf ->
                    val all = mutableMapOf<Long, EffectiveBelief>()
                    for ((_, ids) in snapshot.muscleExerciseIds) {
                        all.putAll(pooling.effective(beliefs, snapshot.seedCoefficients, ids, asOf).effective)
                    }
                    allEffective = all
                },
                observer = { sessionId, asOf, sets, snap, result ->
                    val predictions = sets.groupBy { it.exerciseId }.flatMap { (id, exSets) ->
                        val eff = result.preFoldEffective[id]
                        exSets.sortedBy { it.id }.mapIndexed { idx, s ->
                            SetPrediction(s, idx + 1, eff?.let { it.mu - fold.fatigueShift(idx + 1) })
                        }
                    }
                    observer.onSession(sessionId, asOf, predictions, allEffective, snap.currentBeliefs)
                },
            )
        }
    }
}
