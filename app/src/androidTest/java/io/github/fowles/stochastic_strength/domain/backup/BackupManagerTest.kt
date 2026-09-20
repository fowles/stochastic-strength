package io.github.fowles.stochastic_strength.domain.backup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SavedWorkout
import io.github.fowles.stochastic_strength.data.model.SavedWorkoutExercise
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var manager: BackupManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = WorkoutRepository(db)
        manager = BackupManager(db, repository)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        db.exerciseDao().insert(Exercise(id = 0, name = "Bench Press",
            primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL))
        db.exerciseDao().insert(Exercise(id = 0, name = "Squat",
            primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL))
        val sid = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000, endTime = 2000))
        db.workoutSetDao().insert(WorkoutSet(sessionId = sid, exerciseId = 1, setNumber = 1,
            targetWeight = 60f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4,
            completedAt = 1500))
    }

    @Test
    fun destructiveImport_reproducesRowsAndIds() = runBlocking {
        seed()
        val backup = manager.export()

        // Mutate the DB so we can prove the import replaced it.
        db.workoutSetDao().deleteAll()
        db.workoutSessionDao().deleteAll()
        db.exerciseDao().deleteAll()

        manager.importDestructive(backup)

        assertEquals(backup.exercises, db.exerciseDao().getAll())
        assertEquals(backup.workoutSessions, db.workoutSessionDao().getAll())
        assertEquals(backup.workoutSets, db.workoutSetDao().getAll())
    }

    @Test
    fun additiveImport_matchesByName_andCreatesMissing() = runBlocking {
        // Local library: "Bench Press" exists locally with a different id than in the backup.
        val localBench = db.exerciseDao().insert(Exercise(id = 0, name = "Bench Press",
            primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL))

        // Backup references "Bench Press" at id 5 and a brand-new "Deadlift" at id 6.
        val backup = WorkoutBackup(
            formatVersion = WorkoutBackup.FORMAT_VERSION, dbVersion = WorkoutBackup.DB_VERSION,
            exportedAt = 0,
            exercises = listOf(
                Exercise(id = 5, name = "Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
                Exercise(id = 6, name = "Deadlift", primaryMuscle = MuscleGroup.BACK, equipment = Equipment.BARBELL),
            ),
            knownLocations = emptyList(), locationExcludedExercises = emptyList(),
            workoutSessions = listOf(WorkoutSession(id = 9, startTime = 1000, endTime = 2000)),
            workoutSets = listOf(
                WorkoutSet(id = 1, sessionId = 9, exerciseId = 5, setNumber = 1, targetWeight = 60f, targetReps = 5),
                WorkoutSet(id = 2, sessionId = 9, exerciseId = 6, setNumber = 1, targetWeight = 100f, targetReps = 5),
            ),
            userProfile = emptyList(), baselineOverrides = emptyList(), exerciseHurtState = emptyList(),
        )

        val result = manager.importAdditive(backup)

        assertEquals(1, result.sessionsAdded)
        assertEquals(1, result.exercisesCreated) // only Deadlift
        assertEquals(0, result.setsSkipped)

        val sessions = db.workoutSessionDao().getAll()
        assertEquals(1, sessions.size)
        val newSessionId = sessions.first().id

        val sets = db.workoutSetDao().getAll().sortedBy { it.setNumber }
        assertEquals(2, sets.size)
        // Bench set remapped to the pre-existing local id; all sets point at the new session.
        assertEquals(localBench, sets[0].exerciseId)
        assertTrue(sets.all { it.sessionId == newSessionId })
    }

    @Test
    fun additiveImport_reimportingSameFile_isANoOp() = runBlocking {
        val backup = WorkoutBackup(
            formatVersion = WorkoutBackup.FORMAT_VERSION, dbVersion = WorkoutBackup.DB_VERSION,
            exportedAt = 0,
            exercises = listOf(
                Exercise(id = 5, name = "Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            ),
            knownLocations = emptyList(), locationExcludedExercises = emptyList(),
            workoutSessions = listOf(WorkoutSession(id = 9, startTime = 1000, endTime = 2000)),
            workoutSets = listOf(
                WorkoutSet(id = 1, sessionId = 9, exerciseId = 5, setNumber = 1, targetWeight = 60f, targetReps = 5),
            ),
            userProfile = emptyList(), baselineOverrides = emptyList(), exerciseHurtState = emptyList(),
        )

        val first = manager.importAdditive(backup)
        assertEquals(1, first.sessionsAdded)

        val second = manager.importAdditive(backup)
        assertEquals(0, second.sessionsAdded)

        assertEquals(1, db.workoutSessionDao().getAll().size)
        assertEquals(1, db.workoutSetDao().getAll().size)
    }

    @Test
    fun additiveImport_skipsKnownSession_addsOnlyNewOne() = runBlocking {
        db.exerciseDao().insert(Exercise(id = 0, name = "Bench Press",
            primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL))
        val knownSid = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000, endTime = 2000))
        db.workoutSetDao().insert(WorkoutSet(sessionId = knownSid, exerciseId = 1, setNumber = 1,
            targetWeight = 60f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4,
            completedAt = 1500))

        val backup = WorkoutBackup(
            formatVersion = WorkoutBackup.FORMAT_VERSION, dbVersion = WorkoutBackup.DB_VERSION,
            exportedAt = 0,
            exercises = listOf(
                Exercise(id = 5, name = "Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            ),
            knownLocations = emptyList(), locationExcludedExercises = emptyList(),
            workoutSessions = listOf(
                WorkoutSession(id = 9, startTime = 1000, endTime = 2000), // matches the known session
                WorkoutSession(id = 10, startTime = 3000, endTime = 4000), // new
            ),
            workoutSets = listOf(
                WorkoutSet(id = 1, sessionId = 9, exerciseId = 5, setNumber = 1, targetWeight = 60f, targetReps = 5),
                WorkoutSet(id = 2, sessionId = 10, exerciseId = 5, setNumber = 1, targetWeight = 60f, targetReps = 5),
            ),
            userProfile = emptyList(), baselineOverrides = emptyList(), exerciseHurtState = emptyList(),
        )

        val result = manager.importAdditive(backup)

        assertEquals(1, result.sessionsAdded)
        assertEquals(2, db.workoutSessionDao().getAll().size)
        assertEquals(2, db.workoutSetDao().getAll().size)
    }

    @Test
    fun additiveImport_skipsOpenSessionAlreadyClosedLocally() = runBlocking {
        // A backup taken while a session was still open holds (start, null); locally that same
        // session has since been closed to (start, lastCompletedAt). Same session, one startTime.
        db.exerciseDao().insert(Exercise(id = 0, name = "Bench Press",
            primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL))
        val localSid = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000, endTime = 1500))
        db.workoutSetDao().insert(WorkoutSet(sessionId = localSid, exerciseId = 1, setNumber = 1,
            targetWeight = 60f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4,
            completedAt = 1500))

        val backup = WorkoutBackup(
            formatVersion = WorkoutBackup.FORMAT_VERSION, dbVersion = WorkoutBackup.DB_VERSION,
            exportedAt = 0,
            exercises = listOf(
                Exercise(id = 5, name = "Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            ),
            knownLocations = emptyList(), locationExcludedExercises = emptyList(),
            workoutSessions = listOf(WorkoutSession(id = 9, startTime = 1000, endTime = null)),
            workoutSets = listOf(
                WorkoutSet(id = 1, sessionId = 9, exerciseId = 5, setNumber = 1, targetWeight = 60f,
                    targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4, completedAt = 1500),
            ),
            userProfile = emptyList(), baselineOverrides = emptyList(), exerciseHurtState = emptyList(),
        )

        val result = manager.importAdditive(backup)

        assertEquals(0, result.sessionsAdded)
        assertEquals(1, db.workoutSessionDao().getAll().size)
        assertEquals(1, db.workoutSetDao().getAll().size)
    }

    @Test
    fun additiveImport_leavesProfileUntouched() = runBlocking {
        db.userProfileDao().insert(io.github.fowles.stochastic_strength.data.model.UserProfile(
            id = 1, sex = io.github.fowles.stochastic_strength.data.model.Sex.MALE,
            strengthLevel = io.github.fowles.stochastic_strength.data.model.StrengthLevel.MEDIUM,
            weightUnit = io.github.fowles.stochastic_strength.data.model.WeightUnit.KG,
        ))
        val backup = WorkoutBackup(
            formatVersion = WorkoutBackup.FORMAT_VERSION, dbVersion = WorkoutBackup.DB_VERSION,
            exportedAt = 0, exercises = emptyList(), knownLocations = emptyList(),
            locationExcludedExercises = emptyList(), workoutSessions = emptyList(), workoutSets = emptyList(),
            userProfile = listOf(io.github.fowles.stochastic_strength.data.model.UserProfile(
                id = 1, sex = io.github.fowles.stochastic_strength.data.model.Sex.FEMALE,
                strengthLevel = io.github.fowles.stochastic_strength.data.model.StrengthLevel.LOW,
                weightUnit = io.github.fowles.stochastic_strength.data.model.WeightUnit.LBS,
            )),
            baselineOverrides = emptyList(), exerciseHurtState = emptyList(),
        )

        manager.importAdditive(backup)

        // Local profile preserved (KG/MALE), not overwritten by the backup's LBS/FEMALE.
        assertEquals(io.github.fowles.stochastic_strength.data.model.WeightUnit.KG,
            db.userProfileDao().getProfile()!!.weightUnit)
    }

    @Test
    fun export_includesSavedWorkouts_andDestructiveImportRestoresThem() = runBlocking {
        seed()
        val wid = db.savedWorkoutDao().insert(SavedWorkout(name = "Push", createdAt = 1))
        db.savedWorkoutDao().insertExerciseRows(listOf(
            SavedWorkoutExercise(workoutId = wid, exerciseId = 1, position = 0, reps = 5),
        ))
        val backup = manager.export()
        assertEquals(1, backup.savedWorkouts.size)
        assertEquals(1, backup.savedWorkoutExercises.size)

        db.savedWorkoutDao().deleteAllExerciseRows()
        db.savedWorkoutDao().deleteAll()
        manager.importDestructive(backup)
        assertEquals(backup.savedWorkouts, db.savedWorkoutDao().getAll())
        assertEquals(backup.savedWorkoutExercises, db.savedWorkoutDao().getAllExerciseRows())
    }

    @Test
    fun additiveImport_remapsSavedWorkoutExercisesByName_andSkipsSameName() = runBlocking {
        val localBench = db.exerciseDao().insert(Exercise(id = 0, name = "Bench Press",
            primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL))
        db.savedWorkoutDao().insert(SavedWorkout(name = "Already here", createdAt = 1))

        val backup = WorkoutBackup(
            formatVersion = WorkoutBackup.FORMAT_VERSION, dbVersion = WorkoutBackup.DB_VERSION,
            exportedAt = 0,
            exercises = listOf(
                Exercise(id = 5, name = "Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            ),
            knownLocations = emptyList(), locationExcludedExercises = emptyList(),
            workoutSessions = emptyList(), workoutSets = emptyList(), userProfile = emptyList(),
            baselineOverrides = emptyList(), exerciseHurtState = emptyList(),
            savedWorkouts = listOf(
                SavedWorkout(id = 9, name = "Already here", createdAt = 2),
                SavedWorkout(id = 10, name = "New one", createdAt = 3),
            ),
            savedWorkoutExercises = listOf(
                SavedWorkoutExercise(workoutId = 10, exerciseId = 5, position = 0, reps = 8),
                SavedWorkoutExercise(workoutId = 10, exerciseId = 999, position = 1, reps = null), // unresolvable
            ),
        )
        val result = manager.importAdditive(backup)
        assertEquals(1, result.savedWorkoutsAdded)
        val all = db.savedWorkoutDao().getAll()
        assertEquals(listOf("Already here", "New one"), all.map { it.name })
        val newId = all.first { it.name == "New one" }.id
        val rows = db.savedWorkoutDao().getExerciseRows(newId)
        assertEquals(1, rows.size)
        assertEquals(localBench, rows[0].exerciseId)
        assertEquals(8, rows[0].reps)
    }

    @Test
    fun additiveImport_circuitThatLosesAMember_importsAsSolo() = runBlocking {
        // Arrange exactly as additiveImport_remapsSavedWorkoutExercisesByName_andSkipsSameName does
        // for its unresolvable row, but tag both backup rows sets = 2, circuitId = 0.
        db.exerciseDao().insert(Exercise(id = 0, name = "Bench Press",
            primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL))
        db.savedWorkoutDao().insert(SavedWorkout(name = "Already here", createdAt = 1))

        val backup = WorkoutBackup(
            formatVersion = WorkoutBackup.FORMAT_VERSION, dbVersion = WorkoutBackup.DB_VERSION,
            exportedAt = 0,
            exercises = listOf(
                Exercise(id = 5, name = "Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            ),
            knownLocations = emptyList(), locationExcludedExercises = emptyList(),
            workoutSessions = emptyList(), workoutSets = emptyList(), userProfile = emptyList(),
            baselineOverrides = emptyList(), exerciseHurtState = emptyList(),
            savedWorkouts = listOf(
                SavedWorkout(id = 9, name = "Already here", createdAt = 2),
                SavedWorkout(id = 10, name = "New one", createdAt = 3),
            ),
            savedWorkoutExercises = listOf(
                SavedWorkoutExercise(workoutId = 10, exerciseId = 5, position = 0, reps = 8,
                    sets = 2, circuitId = 0),
                SavedWorkoutExercise(workoutId = 10, exerciseId = 999, position = 1, reps = null,
                    sets = 2, circuitId = 0), // unresolvable
            ),
        )

        // Act
        manager.importAdditive(backup)

        // Assert on the imported workout's rows:
        val rows = db.savedWorkoutDao().getAllExerciseRows()
        assertEquals(1, rows.size)
        assertNull(rows.single().circuitId)
        assertEquals(2, rows.single().sets)
    }
}
