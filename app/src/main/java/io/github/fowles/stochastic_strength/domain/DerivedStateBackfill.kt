package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase

/**
 * Launch-time orchestrator. Both steps are idempotent, so this can run on every launch.
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
