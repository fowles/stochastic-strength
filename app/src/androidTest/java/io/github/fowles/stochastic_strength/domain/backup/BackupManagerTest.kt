package io.github.fowles.stochastic_strength.domain.backup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
}
