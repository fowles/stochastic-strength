package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefSessionStep
import kotlin.math.ln

/**
 * Replays every completed session in order, seeding initial beliefs and applying per-session
 * strength-override rows exactly as the production replay does, folding the belief stack
 * ([BeliefSessionStep]) as it goes. After each session it invokes [SessionObserver]; the caller
 * decides what to do with the result (write derived rows, or record chart samples). The replay is
 * muscle-agnostic; consumers filter.
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

    suspend fun run(db: AppDatabase, snapshot: ReplaySnapshot, observer: SessionObserver) {
        val sigmaSeed2 = beliefConfig.sigmaSeed * beliefConfig.sigmaSeed
        val sigmaOverride2 = beliefConfig.sigmaOverride * beliefConfig.sigmaOverride

        // Init from per-exercise strength overrides (sessionId = null rows).
        val initials = db.exerciseStrengthOverrideDao().getInitials()
        for (init in initials) {
            snapshot.currentBeliefs[init.exerciseId] = Belief(ln(init.e1rm), sigmaSeed2, init.asOf)
        }

        val exerciseOverridesBySession = db.exerciseStrengthOverrideDao().getNonInitials()
            .groupBy { it.sessionId!! }

        val sessions = db.workoutSessionDao().getAll()
            .filter { it.endTime != null }
            .sortedWith(compareBy({ it.endTime!! }, { it.id }))

        for (session in sessions) {
            exerciseOverridesBySession[session.id]?.forEach { o ->
                snapshot.currentBeliefs[o.exerciseId] = Belief(ln(o.e1rm), sigmaOverride2, o.asOf)
            }

            val sets = db.workoutSetDao().getSetsForSession(session.id)
            if (sets.isEmpty()) continue
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
