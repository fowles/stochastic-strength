package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

/**
 * Preloaded replay inputs: completed sessions plus their sets and strength-override rows.
 * Lets [ReplayEngine] run without a database (backtests, and the phase-4 fitter's inner loop).
 * [sessions] may be unsorted and may contain incomplete sessions; the engine filters and sorts.
 */
data class ReplayHistory(
    val sessions: List<WorkoutSession>,
    val setsBySession: Map<Long, List<WorkoutSet>>,
    val initialOverrides: List<ExerciseStrengthOverride>,
    val sessionOverrides: Map<Long, List<ExerciseStrengthOverride>>,
) {
    companion object {
        suspend fun loadFromDb(db: AppDatabase): ReplayHistory {
            val sessions = db.workoutSessionDao().getAll().filter { it.endTime != null }
            // Unfiltered query: the completedAt-filtered variant would drop timestamp-less sets from replay.
            val sets = if (sessions.isEmpty()) emptyMap()
            else db.workoutSetDao().getAllSetsForSessions(sessions.map { it.id }).groupBy { it.sessionId }
            return ReplayHistory(
                sessions = sessions,
                setsBySession = sets,
                initialOverrides = db.exerciseStrengthOverrideDao().getInitials(),
                sessionOverrides = db.exerciseStrengthOverrideDao().getNonInitials().groupBy { it.sessionId!! },
            )
        }
    }
}
