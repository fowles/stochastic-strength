package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase

/**
 * Catch-up runner for one-shot upgrade passes that re-derive state from the user's history.
 *
 * TODO Task 21 (Phase 6): replace with replay-based implementation that calls
 * `ActualRepsBackfill` then `repository.replayDerivedState()`. The version-gating logic
 * (`derivedStateVersion`, `CURRENT_VERSION`) is removed in this phase because `UserProfile`
 * no longer carries `derivedStateVersion`.
 */
class DerivedStateBackfill(
    private val database: AppDatabase,
    private val repository: WorkoutRepository,
) {
    suspend fun run() {
        val profile = database.userProfileDao().getProfile() ?: return
        // TODO Task 21: replace with ActualRepsBackfill + replayDerivedState()
        // No-op stub — Phase 6 rewrites this to use the replay-based pipeline.
        ActualRepsBackfill(database, profile.weightUnit).run()
        repository.recomputeDerivedState()
    }
}
