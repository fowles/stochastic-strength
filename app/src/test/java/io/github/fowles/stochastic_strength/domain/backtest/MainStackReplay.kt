package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimate
import io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjector
import io.github.fowles.stochastic_strength.domain.progression.SessionProgressionStepper
import kotlin.math.ln

/**
 * DB-free mirror of the production replay (ReplayEngine.run) over parsed backup data, scoring
 * main's stack UNMODIFIED (spec Phase 0 constraint). KEEP IN SYNC with ReplayEngine.run:
 * initial overrides seed estimates; session-k override rows apply before session k; sessions
 * iterate sorted by (endTime, id); empty-set sessions are skipped.
 *
 * The one addition: before folding each session, every loaded exercise's projected effective 1RM
 * is captured at now = session.endTime — the forward-chained held-out prediction for that session.
 */
object MainStackReplay {

    fun interface SessionObserver {
        /** [snapshot] is the LIVE mutable replay state, already folded past this session (post-step);
         *  the prediction-time (pre-fold) view is [predictions]. Read synchronously, never retain. */
        fun onSession(sessionId: Long, asOf: Long, sets: List<WorkoutSet>, predictions: Map<Long, Float>, snapshot: ReplaySnapshot)
    }

    fun run(
        data: BacktestData,
        stepper: SessionProgressionStepper = SessionProgressionStepper(),
        projector: MuscleStrengthProjector = MuscleStrengthProjector(),
        observer: SessionObserver,
    ) {
        val snapshot = data.newSnapshot()
        for (init in data.initialOverrides) {
            snapshot.currentEstimates[init.exerciseId] = ExerciseEstimate.seed(init.e1rm, at = init.asOf)
        }
        for (session in data.sessions) {
            data.sessionOverrides[session.id]?.forEach { o ->
                // Same shape as ReplayEngine.run's override row (confidence 1.0).
                snapshot.currentEstimates[o.exerciseId] = ExerciseEstimate(lnE = ln(o.e1rm), confidence = 1.0f, updatedAt = o.asOf)
            }
            val sets = data.setsBySession[session.id].orEmpty()
            if (sets.isEmpty()) continue
            val asOf = session.endTime!!
            val predictions = mutableMapOf<Long, Float>()
            for ((_, ids) in snapshot.muscleExerciseIds) {
                val proj = projector.project(snapshot.currentEstimates, snapshot.seedCoefficients, ids, asOf)
                predictions.putAll(proj.effectiveE1rm)
            }
            stepper.step(sets, snapshot, asOf)
            observer.onSession(session.id, asOf, sets, predictions, snapshot)
        }
    }
}
