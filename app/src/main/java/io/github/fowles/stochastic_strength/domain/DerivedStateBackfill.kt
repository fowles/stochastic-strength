package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase

/**
 * Launch-time orchestrator. Every step is idempotent, so this can run on every launch. It runs
 * async once the UI may already be live, so it never touches open sessions: closing orphans is
 * done synchronously at process start by [io.github.fowles.stochastic_strength.StochasticStrengthApp].
 */
class DerivedStateBackfill(
    private val database: AppDatabase,
    private val repository: WorkoutRepository,
) {
    suspend fun run() {
        val profile = database.userProfileDao().getProfile() ?: return
        ActualRepsBackfill(database, profile.weightUnit).run()
        repository.replayDerivedState()
    }
}
