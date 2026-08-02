package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.AppDatabase
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
        val profile = db.userProfileDao().getProfile()
        val seeds = if (profile == null) {
            ExerciseSeedExpansion.Seeds(emptyList(), emptyMap())
        } else {
            ExerciseSeedExpansion.buildSeeds(
                initialOverrides = db.baselineOverrideDao().getInitials(),
                sessionOverrides = db.baselineOverrideDao().getNonInitials(),
                sex = profile.sex,
                level = profile.strengthLevel,
                exerciseMuscle = snapshot.exerciseMuscle,
                coefById = snapshot.seedCoefficients,
            )
        }
        runCore(
            snapshot = snapshot,
            initialSeeds = seeds.initial,
            sessionSeeds = seeds.bySession,
            sessions = db.workoutSessionDao().getAll(),
            setsForSession = { db.workoutSetDao().getSetsForSession(it) },
            observer = observer,
            beforeSession = beforeSession,
        )
    }

    /**
     * The engine itself: seed beliefs from initial seeds (seedUncertaintySd), then for each completed
     * session in (endTime, id) order apply its session seeds (overrideUncertaintySd), fold its sets, and
     * notify [observer]. [beforeSession] (optional) runs after the seeds but before the fold — a
     * pre-fold inspection hook (the backtest pools held-out beliefs there).
     */
    suspend fun runCore(
        snapshot: ReplaySnapshot,
        initialSeeds: List<SeedBelief>,
        sessionSeeds: Map<Long, List<SeedBelief>>,
        sessions: List<WorkoutSession>,
        setsForSession: suspend (Long) -> List<WorkoutSet>,
        observer: SessionObserver,
        beforeSession: ((beliefs: Map<Long, Belief>, asOf: Long) -> Unit)? = null,
    ) {
        val seedUncertainty = beliefConfig.seedUncertaintySd * beliefConfig.seedUncertaintySd
        val overrideUncertainty = beliefConfig.overrideUncertaintySd * beliefConfig.overrideUncertaintySd

        for (seed in initialSeeds) {
            snapshot.currentBeliefs[seed.exerciseId] = Belief(ln(seed.e1rm), seedUncertainty, seed.asOf)
        }

        val ordered = sessions
            .filter { it.endTime != null }
            .sortedWith(compareBy({ it.endTime!! }, { it.id }))

        for (session in ordered) {
            sessionSeeds[session.id]?.forEach { seed ->
                snapshot.currentBeliefs[seed.exerciseId] = Belief(ln(seed.e1rm), overrideUncertainty, seed.asOf)
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
            observer.onSession(session.id, session.endTime, sets, snapshot, beliefResult)
        }
    }
}
