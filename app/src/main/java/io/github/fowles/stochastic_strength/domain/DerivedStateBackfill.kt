package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase

/**
 * Launch-time orchestrator. Every step is idempotent, so this can run on every launch. Orphaned
 * sessions are closed first, ahead of the replay, so a session process death left open still
 * contributes its sets.
 */
class DerivedStateBackfill(
    private val database: AppDatabase,
    private val repository: WorkoutRepository,
) {
    suspend fun run() {
        repository.closeOrphanedSessions()
        val profile = database.userProfileDao().getProfile() ?: return
        ActualRepsBackfill(database, profile.weightUnit).run()
        repository.replayDerivedState()
    }
}
