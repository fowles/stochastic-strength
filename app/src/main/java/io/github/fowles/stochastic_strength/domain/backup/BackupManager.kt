package io.github.fowles.stochastic_strength.domain.backup

import androidx.room.withTransaction
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.domain.WorkoutRepository

data class AdditiveResult(
    val sessionsAdded: Int,
    val exercisesCreated: Int,
    val locationsCreated: Int,
    val setsSkipped: Int,
)

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

    /**
     * Merges only the backup's sessions + sets into the current data. Exercises and locations
     * are matched to the local library by name; missing ones are created. Each imported session
     * gets a fresh local id; its sets are remapped accordingly. Profile/overrides/hurt-state are
     * left untouched. Sets whose exercise cannot be resolved are skipped.
     */
    suspend fun importAdditive(backup: WorkoutBackup): AdditiveResult {
        var exercisesCreated = 0
        var locationsCreated = 0
        var setsSkipped = 0
        var sessionsAdded = 0

        db.withTransaction {
            // name -> local exercise id
            val exerciseByName = db.exerciseDao().getAll().associate { it.name to it.id }.toMutableMap()
            val backupExerciseById = backup.exercises.associateBy { it.id }
            // name -> local location id
            val locationByName = db.knownLocationDao().getAll().associate { it.name to it.id }.toMutableMap()
            val backupLocationById = backup.knownLocations.associateBy { it.id }

            suspend fun resolveExerciseId(backupExerciseId: Long): Long? {
                val def = backupExerciseById[backupExerciseId] ?: return null
                exerciseByName[def.name]?.let { return it }
                val newId = db.exerciseDao().insert(def.copy(id = 0))
                exerciseByName[def.name] = newId
                exercisesCreated++
                return newId
            }

            suspend fun resolveLocationId(backupLocationId: Long?): Long? {
                if (backupLocationId == null) return null
                val def = backupLocationById[backupLocationId] ?: return null
                locationByName[def.name]?.let { return it }
                val newId = db.knownLocationDao().insert(def.copy(id = 0))
                locationByName[def.name] = newId
                locationsCreated++
                return newId
            }

            val setsBySession = backup.workoutSets.groupBy { it.sessionId }
            for (session in backup.workoutSessions) {
                val newLocationId = resolveLocationId(session.locationId)
                val newSessionId = db.workoutSessionDao().insert(
                    session.copy(id = 0, locationId = newLocationId)
                )
                sessionsAdded++
                for (set in setsBySession[session.id].orEmpty()) {
                    val newExerciseId = resolveExerciseId(set.exerciseId)
                    if (newExerciseId == null) {
                        setsSkipped++
                        continue
                    }
                    db.workoutSetDao().insert(
                        set.copy(id = 0, sessionId = newSessionId, exerciseId = newExerciseId)
                    )
                }
            }
        }
        repository.replayDerivedState()
        return AdditiveResult(sessionsAdded, exercisesCreated, locationsCreated, setsSkipped)
    }
}
