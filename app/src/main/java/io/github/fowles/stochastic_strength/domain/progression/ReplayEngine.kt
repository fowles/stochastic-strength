package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import kotlin.math.ln

/**
 * Replays every completed session in order through [SessionProgressionStepper], seeding initial
 * estimates and applying per-session strength-override rows exactly as the production replay does.
 * After each session it invokes [SessionObserver]; the caller decides what to do with the result
 * (write derived rows, or record chart samples). The replay is muscle-agnostic; consumers filter.
 */
class ReplayEngine(
    private val stepper: SessionProgressionStepper = SessionProgressionStepper(),
) {
    fun interface SessionObserver {
        fun onSession(
            sessionId: Long,
            asOf: Long,
            sets: List<WorkoutSet>,
            snapshot: ReplaySnapshot,
            result: SessionProgressionStepper.StepResult,
        )
    }

    suspend fun run(db: AppDatabase, snapshot: ReplaySnapshot, observer: SessionObserver) {
        // Init from per-exercise strength overrides (sessionId = null rows).
        val initials = db.exerciseStrengthOverrideDao().getInitials()
        for (init in initials) {
            snapshot.currentEstimates[init.exerciseId] = ExerciseEstimate.seed(init.e1rm, at = init.asOf)
        }

        val exerciseOverridesBySession = db.exerciseStrengthOverrideDao().getNonInitials()
            .groupBy { it.sessionId!! }

        val sessions = db.workoutSessionDao().getAll()
            .filter { it.endTime != null }
            .sortedWith(compareBy({ it.endTime!! }, { it.id }))

        for (session in sessions) {
            exerciseOverridesBySession[session.id]?.forEach { o ->
                snapshot.currentEstimates[o.exerciseId] = ExerciseEstimate(
                    lnE = ln(o.e1rm),
                    confidence = 1.0f,
                    updatedAt = o.asOf,
                )
            }

            val sets = db.workoutSetDao().getSetsForSession(session.id)
            if (sets.isEmpty()) continue
            val result = stepper.step(sets, snapshot, session.endTime!!)
            observer.onSession(session.id, session.endTime!!, sets, snapshot, result)
        }
    }
}
