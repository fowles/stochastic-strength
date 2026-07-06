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

    suspend fun run(db: AppDatabase, snapshot: ReplaySnapshot, observer: SessionObserver) =
        run(ReplayHistory.loadFromDb(db), snapshot, observer)

    fun run(history: ReplayHistory, snapshot: ReplaySnapshot, observer: SessionObserver) {
        for (init in history.initialOverrides) {
            snapshot.currentEstimates[init.exerciseId] = ExerciseEstimate.seed(init.e1rm, at = init.asOf)
        }
        val ordered = history.sessions.filter { it.endTime != null }
            .sortedWith(compareBy({ it.endTime!! }, { it.id }))
        for (session in ordered) {
            history.sessionOverrides[session.id]?.forEach { o ->
                snapshot.currentEstimates[o.exerciseId] = ExerciseEstimate(
                    lnE = ln(o.e1rm),
                    confidence = 1.0f,
                    updatedAt = o.asOf,
                )
            }
            val sets = history.setsBySession[session.id].orEmpty()
            if (sets.isEmpty()) continue
            val result = stepper.step(sets, snapshot, session.endTime!!)
            observer.onSession(session.id, session.endTime!!, sets, snapshot, result)
        }
    }
}
