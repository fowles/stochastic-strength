package io.github.fowles.stochastic_strength.domain.backup

import androidx.room.withTransaction
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.domain.CircuitStructure
import io.github.fowles.stochastic_strength.domain.WorkoutRepository

data class AdditiveResult(
    val sessionsAdded: Int,
    val exercisesCreated: Int,
    val locationsCreated: Int,
    val setsSkipped: Int,
    val savedWorkoutsAdded: Int = 0,
)

class BackupManager(
    private val db: AppDatabase,
    private val repository: WorkoutRepository,
) {
    /** Reads all input tables in a single transaction so the snapshot is internally consistent. */
    suspend fun export(): WorkoutBackup = db.withTransaction {
        WorkoutBackup(
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
            savedWorkouts = db.savedWorkoutDao().getAll(),
            savedWorkoutExercises = db.savedWorkoutDao().getAllExerciseRows(),
        )
    }

    /** Wipes all input tables and reloads the backup verbatim (ids preserved), then replays. */
    suspend fun importDestructive(backup: WorkoutBackup) {
        db.withTransaction {
            db.workoutSetDao().deleteAll()
            db.workoutSessionDao().deleteAll()
            db.exerciseHurtStateDao().deleteAll()
            db.baselineOverrideDao().deleteAll()
            db.locationExcludedExerciseDao().deleteAll()
            db.userProfileDao().deleteAll()
            db.savedWorkoutDao().deleteAllExerciseRows()
            db.savedWorkoutDao().deleteAll()
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
            backup.savedWorkouts.forEach { db.savedWorkoutDao().insert(it) }
            db.savedWorkoutDao().insertExerciseRows(backup.savedWorkoutExercises)
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
        var savedWorkoutsAdded = 0

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

            val localWorkoutNames = db.savedWorkoutDao().getAll().map { it.name }.toMutableSet()
            val rowsByWorkout = backup.savedWorkoutExercises.groupBy { it.workoutId }
            for (workout in backup.savedWorkouts) {
                // Unnamed workouts (empty name) are never deduplicated by name.
                if (workout.name.isNotBlank() && workout.name in localWorkoutNames) continue
                val newId = db.savedWorkoutDao().insert(workout.copy(id = 0))
                localWorkoutNames += workout.name
                savedWorkoutsAdded++
                val rows = rowsByWorkout[workout.id].orEmpty().sortedBy { it.position }
                    .mapNotNull { r ->
                        val exerciseId = resolveExerciseId(r.exerciseId) ?: return@mapNotNull null
                        r.copy(id = 0, workoutId = newId, exerciseId = exerciseId)
                    }
                    .let { CircuitStructure.normalize(it) } // a dropped member must not leave a 1-row circuit
                    .mapIndexed { i, r -> r.copy(position = i) }
                db.savedWorkoutDao().insertExerciseRows(rows)
            }
        }
        repository.replayDerivedState()
        return AdditiveResult(sessionsAdded, exercisesCreated, locationsCreated, setsSkipped, savedWorkoutsAdded)
    }
}
