package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.progression.BeliefUpdater
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import io.github.fowles.stochastic_strength.domain.progression.ExerciseBelief
import io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjector
import io.github.fowles.stochastic_strength.domain.progression.ReplayHistory
import io.github.fowles.stochastic_strength.domain.progression.SetObservation
import kotlin.math.ln

/** One load-bearing set with the one-step-ahead prediction the estimator held before folding it. */
data class ScoredSet(
    val sessionId: Long,
    val exerciseId: Long,
    val muscle: MuscleGroup?,
    val endTime: Long,
    val sessionRank: Int,
    val setNumber: Int,
    val obs: SetObservation,
    val predMeanLn: Float,
    val cleanVar: Float,
)

/** The observation's point location on ln(fresh-1RM): the counted point, else interval midpoint,
 *  else the one finite bound. Used for residual diagnostics and the day-offset learning step. */
fun obsLocation(obs: SetObservation): Float = when {
    obs.gaussianLn != null -> obs.gaussianLn
    obs.lowerLn != null && obs.upperLn != null -> (obs.lowerLn + obs.upperLn) / 2f
    obs.lowerLn != null -> obs.lowerLn
    obs.upperLn != null -> obs.upperLn
    else -> 0f
}

/**
 * Replays [history] under [config], emitting the per-set one-step-ahead prediction stream. Replicates
 * [SessionProgressionStepper.step]'s per-exercise interleave (predict from current beliefs, then fold
 * that exercise's sets before the next exercise) so the captured predictions equal production's; Task 2
 * pins that parity. Beliefs/muscle-clock evolve exactly as in the real replay.
 */
fun captureStream(
    history: ReplayHistory,
    config: EstimatorConfig,
    newSnapshot: () -> ReplaySnapshot,
): List<ScoredSet> {
    val updater = BeliefUpdater(config)
    val projector = MuscleStrengthProjector(config)
    val snapshot = newSnapshot()
    for (init in history.initialOverrides) {
        snapshot.currentBeliefs[init.exerciseId] = ExerciseBelief.seed(init.e1rm, at = init.asOf, config = config)
    }
    val out = mutableListOf<ScoredSet>()
    val ordered = history.sessions.filter { it.endTime != null }
        .sortedWith(compareBy({ it.endTime!! }, { it.id }))
    ordered.forEachIndexed { rank, session ->
        history.sessionOverrides[session.id]?.forEach { o ->
            snapshot.currentBeliefs[o.exerciseId] = ExerciseBelief.override(o.e1rm, o.asOf, config)
        }
        val sets = history.setsBySession[session.id].orEmpty()
        if (sets.isEmpty()) return@forEachIndexed
        val asOf = session.endTime!!
        val affected = mutableSetOf<MuscleGroup>()
        sets.groupBy { it.exerciseId }.forEach exercise@{ (id, exSets) ->
            if ((snapshot.seedCoefficients[id] ?: 0f) <= 0f) return@exercise
            var belief = snapshot.currentBeliefs[id] ?: return@exercise
            val muscle = snapshot.exerciseMuscle[id]
            val muscleLast = muscle?.let { snapshot.muscleLastObs[it] }
            // Pre-fold prediction, computed once per exercise from the CURRENT (partially within-session
            // updated) beliefs — exactly as the stepper does before folding this exercise's sets.
            var predMeanLn: Float? = null
            var cleanVar = 0f
            val ids = muscle?.let { snapshot.muscleExerciseIds[it] }
            if (ids != null) {
                val proj = projector.project(
                    beliefs = snapshot.currentBeliefs, seedCoef = snapshot.seedCoefficients,
                    muscleExerciseIds = ids, now = asOf,
                    muscleLastObs = snapshot.muscleLastObs[muscle], equipment = snapshot.exerciseEquipment,
                )
                predMeanLn = proj.effectiveE1rm[id]?.let { ln(it) }
                cleanVar = updater.age(belief, asOf, muscleLast).evidenceVar
            }
            var folded = false
            exSets.sortedBy { it.setNumber }.forEachIndexed { i, set ->
                val obs = SetObservation.from(set, fatigueRank = i + 1, config = config) ?: return@forEachIndexed
                if (predMeanLn != null) {
                    out += ScoredSet(
                        sessionId = session.id, exerciseId = id, muscle = muscle, endTime = asOf,
                        sessionRank = rank, setNumber = set.setNumber, obs = obs,
                        predMeanLn = predMeanLn, cleanVar = cleanVar,
                    )
                }
                belief = if (obs.gaussianLn != null) {
                    updater.foldGaussian(belief, obs.gaussianLn, obs.noiseSd, asOf, muscleLast)
                } else {
                    updater.foldCensored(belief, obs.lowerLn, obs.upperLn, obs.noiseSd, asOf, muscleLast)
                }
                folded = true
            }
            if (folded) {
                snapshot.currentBeliefs[id] = belief
                muscle?.let { affected.add(it) }
            }
        }
        for (m in affected) snapshot.muscleLastObs[m] = asOf
    }
    return out
}
