package io.github.fowles.stochastic_strength.domain.backup

import androidx.room.withTransaction
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.domain.WorkoutRepository

class BackupManager(
    private val db: AppDatabase,
    private val repository: WorkoutRepository,
) {
    suspend fun export(): WorkoutBackup = WorkoutBackup(
        formatVersion = WorkoutBackup.FORMAT_VERSION,
        dbVersion = WorkoutBackup.DB_VERSION,
        exportedAt = System.currentTimeMillis(),
        exercises = db.exerciseDao().getAll(),
        knownLocations = db.knownLocationDao().getAll(),
        locationExcludedExercises = db.locationExcludedExerciseDao().getAll(),
        workoutSessions = db.workoutSessionDao().getAll(),
        workoutSets = db.workoutSetDao().getAll(),
        userProfile = db.userProfileDao().getAll(),
        baselineOverrides = db.baselineOverrideDao().getAll(),
        exerciseHurtState = db.exerciseHurtStateDao().getAll(),
        exerciseStrengthOverrides = db.exerciseStrengthOverrideDao().getAll(),
    )

    /** Wipes all input tables and reloads the backup verbatim (ids preserved), then replays. */
    suspend fun importDestructive(backup: WorkoutBackup) {
        db.withTransaction {
            db.workoutSetDao().deleteAll()
            db.workoutSessionDao().deleteAll()
            db.exerciseHurtStateDao().deleteAll()
            db.exerciseStrengthOverrideDao().deleteAll()
            db.baselineOverrideDao().deleteAll()
            db.locationExcludedExerciseDao().deleteAll()
            db.userProfileDao().deleteAll()
            db.exerciseDao().deleteAll()
            db.knownLocationDao().deleteAll()

            backup.exercises.forEach { db.exerciseDao().insert(it) }
            backup.knownLocations.forEach { db.knownLocationDao().insert(it) }
            db.locationExcludedExerciseDao().insertAll(backup.locationExcludedExercises)
            backup.workoutSessions.forEach { db.workoutSessionDao().insert(it) }
            backup.workoutSets.forEach { db.workoutSetDao().insert(it) }
            backup.userProfile.forEach { db.userProfileDao().insert(it) }
            backup.baselineOverrides.forEach { db.baselineOverrideDao().insert(it) }
            backup.exerciseHurtState.forEach { db.exerciseHurtStateDao().upsert(it) }
            backup.exerciseStrengthOverrides.forEach { db.exerciseStrengthOverrideDao().insert(it) }
        }
        repository.replayDerivedState()
    }
}
