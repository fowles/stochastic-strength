package io.github.fowles.stochastic_strength.domain.backup

import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseHurtState
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

/** In-memory snapshot of every durable input table. Derived state is excluded by design. */
data class WorkoutBackup(
    val formatVersion: Int,
    val dbVersion: Int,
    val exportedAt: Long,
    val exercises: List<Exercise>,
    val knownLocations: List<KnownLocation>,
    val locationExcludedExercises: List<LocationExcludedExercise>,
    val workoutSessions: List<WorkoutSession>,
    val workoutSets: List<WorkoutSet>,
    val userProfile: List<UserProfile>,
    val baselineOverrides: List<BaselineOverride>,
    val exerciseHurtState: List<ExerciseHurtState>,
    val exerciseStrengthOverrides: List<ExerciseStrengthOverride>,
) {
    companion object {
        const val FORMAT = "stochastic-strength-backup"
        const val FORMAT_VERSION = 1
        const val DB_VERSION = 17
    }
}

/** Thrown when a backup file is malformed or targets a different DB version. */
class BackupFormatException(message: String) : Exception(message)
