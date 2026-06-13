package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase

/**
 * Catch-up runner for one-shot upgrade passes that re-derive state from the user's history.
 *
 * The `UserProfile.derivedStateVersion` column records which passes have already executed for this
 * user. On launch, the runner walks any pending steps in order and writes the new version back.
 * Each step must be idempotent — a user may legitimately be at any prior version regardless of
 * which app versions they actually saw, because new steps replace prior single-purpose flags.
 *
 * To add a new pass: add a `when` arm for the next version, do the work, bump [CURRENT_VERSION].
 */
class DerivedStateBackfill(
    private val database: AppDatabase,
    private val repository: WorkoutRepository,
) {
    suspend fun run() {
        val profile = database.userProfileDao().getProfile() ?: return
        if (profile.derivedStateVersion >= CURRENT_VERSION) return

        var version = profile.derivedStateVersion
        while (version < CURRENT_VERSION) {
            when (version) {
                0 -> {
                    // v0 → v1: rerun the actual-reps backfill (idempotent — skips sets that already
                    // have actualReps) and recompute derived state. Catches users who upgraded
                    // through the original v10→v11 release before SeedNormalizer was wired in.
                    ActualRepsBackfill(database, profile.weightUnit).run()
                    repository.recomputeDerivedState()
                }
                else -> error("No derived-state step defined for version $version")
            }
            version += 1
        }

        database.userProfileDao().insert(profile.copy(derivedStateVersion = version))
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}
