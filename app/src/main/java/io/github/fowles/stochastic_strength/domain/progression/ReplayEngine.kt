package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefSessionStep
import kotlin.math.ln

/**
 * Replays every completed session in order, seeding initial beliefs and applying per-session
 * strength-override rows, folding the belief stack ([BeliefSessionStep]) as it goes. After each
 * session it invokes [SessionObserver]; the caller decides what to do with the result (write
 * derived rows, or record chart samples). The replay is muscle-agnostic; consumers filter.
 *
 * [run] is the production DB adapter; [runCore] is the data-source-agnostic engine the backtest
 * replays through as well, so seeding/ordering semantics live exactly once.
 */
class ReplayEngine(
    private val beliefConfig: BeliefConfig = BeliefConfig(),
) {
    private val beliefStep = BeliefSessionStep(beliefConfig)

    fun interface SessionObserver {
        fun onSession(
            sessionId: Long,
            asOf: Long,
            sets: List<WorkoutSet>,
            snapshot: ReplaySnapshot,
            beliefResult: BeliefSessionStep.Result,
        )
    }

    suspend fun run(
        db: AppDatabase,
        snapshot: ReplaySnapshot,
        beforeSession: ((beliefs: Map<Long, Belief>, asOf: Long) -> Unit)? = null,
        observer: SessionObserver,
    ) {
        runCore(
            snapshot = snapshot,
            initialOverrides = db.exerciseStrengthOverrideDao().getInitials(),
            sessionOverrides = db.exerciseStrengthOverrideDao().getNonInitials()
                .groupBy { it.sessionId!! },
            sessions = db.workoutSessionDao().getAll(),
            setsForSession = { db.workoutSetDao().getSetsForSession(it) },
            observer = observer,
            beforeSession = beforeSession,
        )
    }

    /**
     * The engine itself: seed beliefs from initial override rows (sigmaSeed), then for each
     * completed session in (endTime, id) order apply its override rows (sigmaOverride), fold its
     * sets, and notify [observer]. [beforeSession] (optional) runs after the overrides but before
     * the fold — a pre-fold inspection hook (the backtest pools held-out beliefs there).
     */
    suspend fun runCore(
        snapshot: ReplaySnapshot,
        initialOverrides: List<ExerciseStrengthOverride>,
        sessionOverrides: Map<Long, List<ExerciseStrengthOverride>>,
        sessions: List<WorkoutSession>,
        setsForSession: suspend (Long) -> List<WorkoutSet>,
        observer: SessionObserver,
        beforeSession: ((beliefs: Map<Long, Belief>, asOf: Long) -> Unit)? = null,
    ) {
        val sigmaSeed2 = beliefConfig.sigmaSeed * beliefConfig.sigmaSeed
        val sigmaOverride2 = beliefConfig.sigmaOverride * beliefConfig.sigmaOverride

        // Init from per-exercise strength overrides (sessionId = null rows).
        for (init in initialOverrides) {
            snapshot.currentBeliefs[init.exerciseId] = Belief(ln(init.e1rm), sigmaSeed2, init.asOf)
        }

        val ordered = sessions
            .filter { it.endTime != null }
            .sortedWith(compareBy({ it.endTime!! }, { it.id }))

        for (session in ordered) {
            sessionOverrides[session.id]?.forEach { o ->
                snapshot.currentBeliefs[o.exerciseId] = Belief(ln(o.e1rm), sigmaOverride2, o.asOf)
            }

            val sets = setsForSession(session.id)
            if (sets.isEmpty()) continue
            beforeSession?.invoke(snapshot.currentBeliefs, session.endTime!!)
            val beliefResult = beliefStep.step(
                beliefs = snapshot.currentBeliefs,
                sets = sets,
                seedCoef = snapshot.seedCoefficients,
                exerciseMuscle = snapshot.exerciseMuscle,
                muscleExerciseIds = snapshot.muscleExerciseIds,
                asOf = session.endTime!!,
            )
            observer.onSession(session.id, session.endTime!!, sets, snapshot, beliefResult)
        }
    }
}
